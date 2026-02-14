package com.budgetiq.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * JavaScript bridge for scheduling daily reminder notifications.
 * Exposed to WebView as window.BudgetIQReminder
 *
 * Supports 3 reminder slots: morning, afternoon, evening.
 * Each slot has: enabled (bool), hour (0-23), minute (0-59), custom title/body.
 *
 * JS API:
 *   window.BudgetIQReminder.setReminder(slot, hour, minute)
 *   window.BudgetIQReminder.setCustomMessage(slot, title, body)
 *   window.BudgetIQReminder.cancelReminder(slot)
 *   window.BudgetIQReminder.getSettings() → JSON string
 *   window.BudgetIQReminder.enableDefaults() → enables 3 default reminders
 */
public class ReminderScheduler {

    private static final String PREFS = ReminderReceiver.PREFS_NAME;
    private final Context context;

    // Request codes for PendingIntents (unique per slot)
    private static final int RC_MORNING = 300;
    private static final int RC_AFTERNOON = 301;
    private static final int RC_EVENING = 302;

    public ReminderScheduler(Context context) {
        this.context = context;
    }

    /**
     * Enable a reminder at the specified time.
     * @param slot "morning", "afternoon", or "evening"
     * @param hour 0-23
     * @param minute 0-59
     */
    @JavascriptInterface
    public void setReminder(String slot, int hour, int minute) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(slot + "_enabled", true)
                .putInt(slot + "_hour", hour)
                .putInt(slot + "_minute", minute)
                .apply();
        scheduleSlot(context, slot);
    }

    /**
     * Set custom title and body for a slot's notification.
     * If empty, random default messages will be used.
     */
    @JavascriptInterface
    public void setCustomMessage(String slot, String title, String body) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(slot + "_title", title)
                .putString(slot + "_body", body)
                .apply();
    }

    /**
     * Cancel a reminder slot.
     */
    @JavascriptInterface
    public void cancelReminder(String slot) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(slot + "_enabled", false).apply();

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getPendingIntent(context, slot);
        if (am != null && pi != null) {
            am.cancel(pi);
        }
    }

    /**
     * Get current reminder settings as JSON.
     */
    @JavascriptInterface
    public String getSettings() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject json = new JSONObject();
            for (String slot : new String[]{"morning", "afternoon", "evening"}) {
                JSONObject s = new JSONObject();
                s.put("enabled", prefs.getBoolean(slot + "_enabled", false));
                s.put("hour", prefs.getInt(slot + "_hour", getDefaultHour(slot)));
                s.put("minute", prefs.getInt(slot + "_minute", 0));
                s.put("title", prefs.getString(slot + "_title", ""));
                s.put("body", prefs.getString(slot + "_body", ""));
                json.put(slot, s);
            }
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Enable all 3 default reminders with standard times.
     * Morning: 8:00 AM, Afternoon: 1:00 PM, Evening: 9:00 PM
     */
    @JavascriptInterface
    public void enableDefaults() {
        setReminder("morning", 8, 0);
        setReminder("afternoon", 13, 0);
        setReminder("evening", 21, 0);
    }

    /**
     * Check if any reminders are enabled.
     */
    @JavascriptInterface
    public boolean hasActiveReminders() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean("morning_enabled", false)
                || prefs.getBoolean("afternoon_enabled", false)
                || prefs.getBoolean("evening_enabled", false);
    }

    // ---- Static scheduling logic (also used by BootReceiver & ReminderReceiver) ----

    static void scheduleSlot(Context context, String slot) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(slot + "_enabled", false)) return;

        int hour = prefs.getInt(slot + "_hour", getDefaultHour(slot));
        int minute = prefs.getInt(slot + "_minute", 0);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // If time already passed today, schedule for tomorrow
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getPendingIntent(context, slot);

        if (am != null && pi != null) {
            // Use setAndAllowWhileIdle for reliable delivery in Doze mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        }
    }

    static void scheduleAll(Context context) {
        for (String slot : new String[]{"morning", "afternoon", "evening"}) {
            scheduleSlot(context, slot);
        }
    }

    private static PendingIntent getPendingIntent(Context context, String slot) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction("com.budgetiq.REMINDER_" + slot.toUpperCase());
        intent.putExtra(ReminderReceiver.EXTRA_SLOT, slot);
        int rc;
        switch (slot) {
            case "afternoon":
                rc = RC_AFTERNOON;
                break;
            case "evening":
                rc = RC_EVENING;
                break;
            default:
                rc = RC_MORNING;
        }
        return PendingIntent.getBroadcast(context, rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int getDefaultHour(String slot) {
        switch (slot) {
            case "afternoon":
                return 13;
            case "evening":
                return 21;
            default:
                return 8;
        }
    }
}
