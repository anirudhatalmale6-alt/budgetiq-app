package com.budgetiq.app;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for notifications from banking/messaging apps and extracts transaction data.
 * No SMS permission needed - reads notification content instead.
 */
public class TransactionNotificationListener extends NotificationListenerService {

    private static final String PREFS_NAME = "budgetiq_transactions";
    private static final String KEY_TRANSACTIONS = "pending_transactions";
    private static final String KEY_LAST_ID = "last_notification_id";

    // Bank SMS patterns
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:INR|Rs\\.?|₹)\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEBIT_PATTERN = Pattern.compile(
            "(?:debited|debit|spent|paid|purchase|withdrawn|sent|payment|transferred)",
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

    // Messaging app packages (SMS apps)
    private static final String[] SMS_PACKAGES = {
            "com.google.android.apps.messaging",  // Google Messages
            "com.samsung.android.messaging",       // Samsung Messages
            "com.android.mms",                     // Default SMS
            "com.sonyericsson.conversations",      // Sony Messages
            "com.oneplus.mms",                     // OnePlus Messages
            "com.xiaomi.mms",                      // Xiaomi Messages
            "com.miui.mms",                        // MIUI Messages
            "com.oppo.mms",                        // Oppo Messages
            "com.vivo.mms",                        // Vivo Messages
            "com.realme.mms",                      // Realme Messages
            "com.asus.mms",                        // Asus Messages
            "org.thoughtcrime.securesms",          // Signal
    };

    // Known Indian bank/fintech app packages (send transaction notifications directly)
    private static final String[] BANK_APP_PACKAGES = {
            "com.csam.icici.bank.imobile",         // ICICI iMobile
            "com.snapwork.hdfc",                   // HDFC Mobile Banking
            "com.sbi.lotusintouch",                // SBI YONO
            "com.axis.mobile",                     // Axis Mobile
            "com.msf.koenig.bank.kotak",           // Kotak Mobile Banking
            "com.bob.bank.bobmworld",              // BOB World
            "com.pnb.ebb",                         // PNB ONE
            "com.canarabank.mobility",             // Canara ai1
            "net.one97.paytm",                     // Paytm
            "com.phonepe.app",                     // PhonePe
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "in.amazon.mShop.android.shopping",    // Amazon
            "in.org.npci.upiapp",                  // BHIM
            "com.whatsapp",                        // WhatsApp (payment alerts)
            "com.bajajfinserv",                    // Bajaj Finserv
            "com.lendingkart.finance",             // Lendingkart
            "com.cred.android",                    // CRED
            "com.freecharge.android",              // FreeCharge
            "com.dreamplug.androidapp",            // CRED
    };

    // Known bank sender keywords in notification title
    private static final Pattern BANK_SENDER = Pattern.compile(
            "(?:SBI|HDFC|ICICI|AXIS|KOTAK|BOB|PNB|BOI|CANARA|UNION|IDBI|CITI|PAYTM|" +
            "GPAY|PHONEPE|AMAZON|BAJAJ|AMEX|RBL|FEDERAL|INDUS|YES|IDFCF|HSBC|" +
            "HDFCBK|ICICIB|SBIINB|AXISBK|KOTAKB|BOBIN)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();

            // Only process notifications from messaging apps
            if (!isMessagingApp(packageName)) return;

            Notification notification = sbn.getNotification();
            if (notification == null) return;

            Bundle extras = notification.extras;
            if (extras == null) return;

            String title = extras.getString(Notification.EXTRA_TITLE, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");

            // Use bigText if available (contains full SMS content)
            String body = (bigText != null && !bigText.isEmpty()) ? bigText : text;
            if (body == null || body.isEmpty()) return;

            // Check if this looks like a bank transaction
            if (!isBankTransaction(title, body)) return;

            // Parse the transaction
            JSONObject txn = parseTransaction(body, title, sbn.getPostTime());
            if (txn == null) return;

            // Store in SharedPreferences
            storeTransaction(txn);

        } catch (Exception e) {
            // Silently ignore errors
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not needed
    }

    private boolean isMessagingApp(String packageName) {
        if (packageName == null) return false;
        // Check SMS apps
        for (String pkg : SMS_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        // Check bank/fintech apps
        for (String pkg : BANK_APP_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        // Also accept any app with bank/finance/pay in package name
        String lower = packageName.toLowerCase();
        return lower.contains("bank") || lower.contains("finserv") || lower.contains("finance")
                || lower.contains(".pay") || lower.contains("wallet") || lower.contains("upi");
    }

    private boolean isBankTransaction(String title, String body) {
        // Check if sender/title matches bank pattern
        boolean isBankSender = BANK_SENDER.matcher(title).find();

        // Check if body has transaction keywords + amount
        boolean hasAmount = AMOUNT_PATTERN.matcher(body).find();
        boolean hasTransaction = DEBIT_PATTERN.matcher(body).find() || CREDIT_PATTERN.matcher(body).find();
        boolean hasAccount = ACCOUNT_PATTERN.matcher(body).find();

        return (isBankSender && hasAmount) || (hasAmount && (hasTransaction || hasAccount));
    }

    private JSONObject parseTransaction(String body, String sender, long timestamp) {
        try {
            Matcher amtMatcher = AMOUNT_PATTERN.matcher(body);
            if (!amtMatcher.find()) return null;
            double amount = Double.parseDouble(amtMatcher.group(1).replace(",", ""));
            if (amount <= 0) return null;

            // Type
            String type = "debit";
            if (CREDIT_PATTERN.matcher(body).find()) {
                type = "credit";
            }

            // Account
            String account = "";
            Matcher accMatcher = ACCOUNT_PATTERN.matcher(body);
            if (accMatcher.find()) {
                account = "XX" + accMatcher.group(1);
            }

            // Payment method
            String method = "";
            Matcher upiMatcher = UPI_PATTERN.matcher(body);
            if (upiMatcher.find()) {
                method = upiMatcher.group().toUpperCase();
            }

            // Merchant
            String merchant = "";
            Matcher merchMatcher = MERCHANT_PATTERN.matcher(body);
            if (merchMatcher.find()) {
                merchant = merchMatcher.group(1).trim()
                        .replaceAll("\\s+on$|\\s+at$|\\s+dated$", "").trim();
            }

            // Balance
            double balance = -1;
            Matcher balMatcher = BALANCE_PATTERN.matcher(body);
            if (balMatcher.find()) {
                balance = Double.parseDouble(balMatcher.group(1).replace(",", ""));
            }

            // Date
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
            txn.put("sender", sender);
            txn.put("body", body);
            txn.put("processed", false);

            return txn;
        } catch (Exception e) {
            return null;
        }
    }

    private void storeTransaction(JSONObject txn) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String existing = prefs.getString(KEY_TRANSACTIONS, "[]");
            JSONArray arr = new JSONArray(existing);

            // Avoid duplicates (check timestamp + amount)
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ex = arr.getJSONObject(i);
                if (ex.getLong("timestamp") == txn.getLong("timestamp")
                        && ex.getDouble("amount") == txn.getDouble("amount")) {
                    return; // Duplicate
                }
            }

            arr.put(txn);

            // Keep only last 100 transactions
            while (arr.length() > 100) {
                arr.remove(0);
            }

            prefs.edit().putString(KEY_TRANSACTIONS, arr.toString()).apply();
        } catch (Exception e) {
            // Ignore
        }
    }
}
