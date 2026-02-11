package com.budgetiq.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JavaScript bridge for accessing detected transactions from notifications.
 * Exposed to WebView as window.BudgetIQSms (same name for web compatibility)
 */
public class NotificationBridge {

    private static final String PREFS_NAME = "budgetiq_transactions";
    private static final String KEY_TRANSACTIONS = "pending_transactions";

    private final Context context;

    public NotificationBridge(Context context) {
        this.context = context;
    }

    /**
     * Check if notification listener permission is enabled
     */
    @JavascriptInterface
    public boolean hasSmsPermission() {
        return isNotificationListenerEnabled();
    }

    /**
     * Open notification listener settings so user can enable it
     */
    @JavascriptInterface
    public void requestSmsPermission() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).openNotificationListenerSettings();
        }
    }

    /**
     * Get unprocessed transactions since a given timestamp
     */
    @JavascriptInterface
    public String getUnprocessedTransactions(long lastProcessedTimestamp) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String stored = prefs.getString(KEY_TRANSACTIONS, "[]");
            JSONArray all = new JSONArray(stored);
            JSONArray result = new JSONArray();

            for (int i = 0; i < all.length(); i++) {
                JSONObject txn = all.getJSONObject(i);
                if (txn.getLong("timestamp") > lastProcessedTimestamp
                        && !txn.optBoolean("processed", false)) {
                    result.put(txn);
                }
            }

            return result.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Get all recent transactions (last N days)
     */
    @JavascriptInterface
    public String getRecentTransactions(int days) {
        try {
            long since = System.currentTimeMillis() - ((long) days * 24 * 60 * 60 * 1000);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String stored = prefs.getString(KEY_TRANSACTIONS, "[]");
            JSONArray all = new JSONArray(stored);
            JSONArray result = new JSONArray();

            for (int i = 0; i < all.length(); i++) {
                JSONObject txn = all.getJSONObject(i);
                if (txn.getLong("timestamp") > since) {
                    result.put(txn);
                }
            }

            return result.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Mark transactions as processed (by timestamps)
     */
    @JavascriptInterface
    public void markProcessed(String timestampsJson) {
        try {
            JSONArray timestamps = new JSONArray(timestampsJson);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String stored = prefs.getString(KEY_TRANSACTIONS, "[]");
            JSONArray all = new JSONArray(stored);

            for (int i = 0; i < all.length(); i++) {
                JSONObject txn = all.getJSONObject(i);
                long ts = txn.getLong("timestamp");
                for (int j = 0; j < timestamps.length(); j++) {
                    if (timestamps.getLong(j) == ts) {
                        txn.put("processed", true);
                        break;
                    }
                }
            }

            prefs.edit().putString(KEY_TRANSACTIONS, all.toString()).apply();
        } catch (Exception e) {
            // Ignore
        }
    }

    private boolean isNotificationListenerEnabled() {
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabledListeners == null) return false;
        ComponentName myComponent = new ComponentName(context, TransactionNotificationListener.class);
        return enabledListeners.contains(myComponent.flattenToString());
    }
}
