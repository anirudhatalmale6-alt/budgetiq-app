package com.budgetiq.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript bridge for reading and parsing bank transaction SMS.
 * Exposed to WebView as window.BudgetIQSms
 */
public class SmsBridge {

    private final Context context;

    // Patterns for Indian bank SMS
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:INR|Rs\\.?|₹)\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEBIT_PATTERN = Pattern.compile(
            "(?:debited|debit|spent|paid|purchase|withdrawn|txn|sent|payment|transferred)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREDIT_PATTERN = Pattern.compile(
            "(?:credited|credit|received|refund|cashback|reversed|deposited)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?:a/c|acct|account|card)\\s*(?:no\\.?|#|ending)?\\s*[xX*]*\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPI_PATTERN = Pattern.compile(
            "(?:UPI|IMPS|NEFT|RTGS|NACH)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MERCHANT_PATTERN = Pattern.compile(
            "(?:to|at|for|from|trf to|trf from|info:?)\\s+([A-Za-z][A-Za-z0-9 .&'-]{2,30})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "(?:avl\\.?\\s*bal|available\\s*balance|bal(?:ance)?)[:\\s]*(?:INR|Rs\\.?|₹)?\\s*([\\d,]+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE);

    // Known bank sender IDs
    private static final Pattern BANK_SENDER = Pattern.compile(
            "(?:SBI|HDFC|ICICI|AXIS|KOTAK|BOB|PNB|BOI|CANARA|UNION|IDBI|CITI|PAYTM|" +
            "GPAY|PHONEPE|AMAZON|BAJAJ|AMEX|RBL|FEDERAL|INDUS|YES|IDFCF|HSBC|" +
            "SCSBNK|JKBANK|KARNAT|SYNDIC|MAHABK|DENA|OBC|ALLAHD|BNKBRD|" +
            "HDFCBK|ICICIB|SBIINB|ATMSBI|CBSSBI|AXISBK|KOTAKB|BOBIN)",
            Pattern.CASE_INSENSITIVE);

    public SmsBridge(Context context) {
        this.context = context;
    }

    /**
     * Called from JavaScript: window.BudgetIQSms.getRecentTransactions(days)
     * Returns JSON array of parsed bank transaction SMS from last N days
     */
    @JavascriptInterface
    public String getRecentTransactions(int days) {
        try {
            JSONArray transactions = new JSONArray();
            long since = System.currentTimeMillis() - ((long) days * 24 * 60 * 60 * 1000);

            ContentResolver cr = context.getContentResolver();
            Uri smsUri = Uri.parse("content://sms/inbox");

            Cursor cursor = cr.query(smsUri,
                    new String[]{"_id", "address", "body", "date", "read"},
                    "date > ?",
                    new String[]{String.valueOf(since)},
                    "date DESC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);
                    long date = cursor.getLong(3);

                    // Only process SMS from bank-like senders
                    if (isBankSms(address, body)) {
                        JSONObject txn = parseSms(body, address, date);
                        if (txn != null) {
                            transactions.put(txn);
                        }
                    }
                }
                cursor.close();
            }

            return transactions.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Called from JavaScript: window.BudgetIQSms.getUnprocessedTransactions(lastProcessedTimestamp)
     * Returns new transactions since the last processed timestamp
     */
    @JavascriptInterface
    public String getUnprocessedTransactions(long lastProcessedTimestamp) {
        try {
            JSONArray transactions = new JSONArray();

            ContentResolver cr = context.getContentResolver();
            Uri smsUri = Uri.parse("content://sms/inbox");

            Cursor cursor = cr.query(smsUri,
                    new String[]{"_id", "address", "body", "date", "read"},
                    "date > ?",
                    new String[]{String.valueOf(lastProcessedTimestamp)},
                    "date DESC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);
                    long date = cursor.getLong(3);

                    if (isBankSms(address, body)) {
                        JSONObject txn = parseSms(body, address, date);
                        if (txn != null) {
                            transactions.put(txn);
                        }
                    }
                }
                cursor.close();
            }

            return transactions.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Called from JavaScript: window.BudgetIQSms.hasSmsPermission()
     */
    @JavascriptInterface
    public boolean hasSmsPermission() {
        return context.checkSelfPermission(android.Manifest.permission.READ_SMS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called from JavaScript: window.BudgetIQSms.requestSmsPermission()
     * Triggers the native permission dialog
     */
    @JavascriptInterface
    public void requestSmsPermission() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).requestSmsPermissionFromJs();
        }
    }

    private boolean isBankSms(String address, String body) {
        if (address == null || body == null) return false;

        // Check sender pattern (bank SMS usually come from short codes or alphanumeric IDs)
        if (BANK_SENDER.matcher(address).find()) return true;

        // Check if body contains transaction keywords with amount
        boolean hasAmount = AMOUNT_PATTERN.matcher(body).find();
        boolean hasTransaction = DEBIT_PATTERN.matcher(body).find() || CREDIT_PATTERN.matcher(body).find();
        boolean hasAccount = ACCOUNT_PATTERN.matcher(body).find();

        return hasAmount && (hasTransaction || hasAccount);
    }

    private JSONObject parseSms(String body, String address, long timestamp) {
        try {
            // Extract amount
            Matcher amtMatcher = AMOUNT_PATTERN.matcher(body);
            if (!amtMatcher.find()) return null;
            double amount = Double.parseDouble(amtMatcher.group(1).replace(",", ""));
            if (amount <= 0) return null;

            // Determine debit or credit
            String type = "debit"; // default
            if (CREDIT_PATTERN.matcher(body).find()) {
                type = "credit";
            }

            // Extract account number (last 4 digits)
            String account = "";
            Matcher accMatcher = ACCOUNT_PATTERN.matcher(body);
            if (accMatcher.find()) {
                account = "XX" + accMatcher.group(1);
            }

            // Extract payment method
            String method = "";
            Matcher upiMatcher = UPI_PATTERN.matcher(body);
            if (upiMatcher.find()) {
                method = upiMatcher.group().toUpperCase();
            }

            // Extract merchant/source
            String merchant = "";
            Matcher merchMatcher = MERCHANT_PATTERN.matcher(body);
            if (merchMatcher.find()) {
                merchant = merchMatcher.group(1).trim();
                // Clean up trailing noise
                merchant = merchant.replaceAll("\\s+on$|\\s+at$|\\s+dated$", "").trim();
            }

            // Extract available balance
            double balance = -1;
            Matcher balMatcher = BALANCE_PATTERN.matcher(body);
            if (balMatcher.find()) {
                balance = Double.parseDouble(balMatcher.group(1).replace(",", ""));
            }

            // Format date
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String dateStr = sdf.format(new Date(timestamp));

            JSONObject txn = new JSONObject();
            txn.put("amount", amount);
            txn.put("type", type);
            txn.put("account", account);
            txn.put("method", method);
            txn.put("merchant", merchant);
            txn.put("balance", balance);
            txn.put("date", dateStr);
            txn.put("timestamp", timestamp);
            txn.put("sender", address);
            txn.put("body", body);
            txn.put("processed", false);

            return txn;
        } catch (Exception e) {
            return null;
        }
    }
}
