package com.budgetiq.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * BroadcastReceiver that fires at scheduled times to show daily reminder notifications.
 * Supports 3 reminder slots: morning, afternoon, evening.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String CHANNEL_REMINDERS = "daily_reminders";
    static final String PREFS_NAME = "budgetiq_reminders";
    static final String EXTRA_SLOT = "reminder_slot";

    // Notification IDs per slot (fixed so they replace previous)
    static final int NOTIF_ID_MORNING = 200;
    static final int NOTIF_ID_AFTERNOON = 201;
    static final int NOTIF_ID_EVENING = 202;

    // Default messages per slot
    private static final String[][] MORNING_MESSAGES = {
            {"Good Morning!", "Start your day by logging yesterday's expenses."},
            {"Rise & Track!", "A quick budget check keeps your finances on track."},
            {"Morning Review", "Did you log all expenses from yesterday?"},
            {"Budget Check", "Take 30 seconds to update your spending log."},
            {"New Day, New Budget", "Log your expenses and stay on top of your money."}
    };

    private static final String[][] AFTERNOON_MESSAGES = {
            {"Midday Check-in", "How's your spending going today? Log your expenses."},
            {"Budget Reminder", "Don't forget to track your afternoon expenses."},
            {"Halfway There!", "Check if you're within your daily budget limit."},
            {"Quick Update", "Take a moment to log any recent spending."},
            {"Stay On Track", "Review your budget before the day ends."}
    };

    private static final String[][] EVENING_MESSAGES = {
            {"Evening Wrap-up", "Log today's expenses before you forget!"},
            {"Daily Review", "How did your spending go today? Check your budget."},
            {"End of Day", "Take 1 minute to log any remaining expenses."},
            {"Budget Summary", "Review your spending and plan for tomorrow."},
            {"Before You Sleep", "Quick check - did you track all expenses today?"}
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        createChannel(context);

        String slot = intent.getStringExtra(EXTRA_SLOT);
        if (slot == null) slot = "morning";

        // Check if this slot is enabled
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(slot + "_enabled", false)) return;

        // Check for custom message
        String customTitle = prefs.getString(slot + "_title", "");
        String customBody = prefs.getString(slot + "_body", "");

        String title;
        String body;
        int notifId;

        if (!customTitle.isEmpty() && !customBody.isEmpty()) {
            title = customTitle;
            body = customBody;
        } else {
            // Pick a random message from the slot's pool
            String[][] messages;
            switch (slot) {
                case "afternoon":
                    messages = AFTERNOON_MESSAGES;
                    break;
                case "evening":
                    messages = EVENING_MESSAGES;
                    break;
                default:
                    messages = MORNING_MESSAGES;
            }
            int idx = (int) (Math.random() * messages.length);
            title = messages[idx][0];
            body = messages[idx][1];
        }

        switch (slot) {
            case "afternoon":
                notifId = NOTIF_ID_AFTERNOON;
                break;
            case "evening":
                notifId = NOTIF_ID_EVENING;
                break;
            default:
                notifId = NOTIF_ID_MORNING;
        }

        // Build and show notification
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, notifId, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            nm.notify(notifId, builder.build());
        } catch (Exception e) {
            // Permission not granted
        }

        // Re-schedule for next day
        ReminderScheduler.scheduleSlot(context, slot);
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_REMINDERS, "Daily Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Daily budget tracking reminders");
            nm.createNotificationChannel(channel);
        }
    }
}
