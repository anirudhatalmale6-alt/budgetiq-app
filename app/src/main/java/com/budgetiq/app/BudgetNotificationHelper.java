package com.budgetiq.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * JavaScript bridge for sending local notifications (budget alerts, reminders).
 * Exposed to WebView as window.BudgetIQNotify
 */
public class BudgetNotificationHelper {

    private static final String CHANNEL_BUDGET = "budget_alerts";
    private static final String CHANNEL_EMI = "emi_reminders";
    private static final String CHANNEL_TRANSACTION = "transactions";

    private final Context context;
    private int notificationId = 100;

    public BudgetNotificationHelper(Context context) {
        this.context = context;
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);

            NotificationChannel budgetChannel = new NotificationChannel(
                    CHANNEL_BUDGET, "Budget Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT);
            budgetChannel.setDescription("Alerts when you exceed budget limits");

            NotificationChannel emiChannel = new NotificationChannel(
                    CHANNEL_EMI, "EMI & Due Date Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            emiChannel.setDescription("Reminders for EMI payments and credit card due dates");

            NotificationChannel txnChannel = new NotificationChannel(
                    CHANNEL_TRANSACTION, "Transaction Detected",
                    NotificationManager.IMPORTANCE_DEFAULT);
            txnChannel.setDescription("Notifications when new bank transactions are detected");

            nm.createNotificationChannel(budgetChannel);
            nm.createNotificationChannel(emiChannel);
            nm.createNotificationChannel(txnChannel);
        }
    }

    /**
     * Send a budget alert notification
     * Called from JS: window.BudgetIQNotify.budgetAlert(title, message)
     */
    @JavascriptInterface
    public void budgetAlert(String title, String message) {
        sendNotification(CHANNEL_BUDGET, title, message, R.mipmap.ic_launcher);
    }

    /**
     * Send an EMI/due date reminder
     * Called from JS: window.BudgetIQNotify.emiReminder(title, message)
     */
    @JavascriptInterface
    public void emiReminder(String title, String message) {
        sendNotification(CHANNEL_EMI, title, message, R.mipmap.ic_launcher);
    }

    /**
     * Send a transaction detected notification
     * Called from JS: window.BudgetIQNotify.transactionDetected(title, message)
     */
    @JavascriptInterface
    public void transactionDetected(String title, String message) {
        sendNotification(CHANNEL_TRANSACTION, title, message, R.mipmap.ic_launcher);
    }

    /**
     * Check if notifications are enabled
     */
    @JavascriptInterface
    public boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * Request notification permission (Android 13+)
     */
    @JavascriptInterface
    public void requestNotificationPermission() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).requestNotificationPermissionFromJs();
        }
    }

    private void sendNotification(String channel, String title, String message, int icon) {
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel)
                    .setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            nm.notify(notificationId++, builder.build());
        } catch (Exception e) {
            // Permission might not be granted
        }
    }
}
