package com.andunie.reminder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Map;

/**
 * Scheduling, persistence and display of reminders.
 *
 * <p>Everything runs through {@link AlarmManager}: the app process is never kept alive and no
 * background service is used. The process may be killed right after {@link #schedule}, and the
 * alarm still wakes the device, starts {@link ReminderReceiver} and posts the notification.
 */
final class Reminders {
    /** Preset delays in minutes, in display order. Keep in sync with res/xml/shortcuts.xml. */
    static final int[] PRESETS_MIN = {10, 30, 60};

    private static final String TAG = "SimpleReminder";
    private static final String PREFS = "reminders";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_PREFIX = "r:";
    private static final String KEY_SILENT = "silent";
    private static final String CHANNEL = "reminders";
    private static final String CHANNEL_SILENT = "reminders_silent";
    /** Appended to an entry's timestamp to mark a silent reminder: "<epoch ms>,s". */
    private static final String SILENT_SUFFIX = ",s";
    static final String EXTRA_ID = "id";

    private Reminders() {}

    static String label(int minutes) {
        return minutes < 60 ? minutes + " min" : (minutes / 60) + (minutes == 60 ? " hour" : " hours");
    }

    /** The sound choice of the most recently scheduled reminder; the default for the next one. */
    static boolean lastSilent(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SILENT, false);
    }

    /**
     * Stores the reminder and arms the alarm.
     *
     * @return the epoch millisecond at which it will fire, or -1 if the alarm could not be armed.
     */
    static long schedule(Context ctx, String text, int minutes, boolean silent) {
        return scheduleAt(ctx, text, System.currentTimeMillis() + minutes * 60_000L, silent);
    }

    /** Schedules after an arbitrary delay; used by the long-press self-test. */
    static long scheduleIn(Context ctx, String text, long delayMs, boolean silent) {
        return scheduleAt(ctx, text, System.currentTimeMillis() + delayMs, silent);
    }

    private static long scheduleAt(Context ctx, String text, long at, boolean silent) {
        SharedPreferences prefs = prefs(ctx);
        int id = prefs.getInt(KEY_NEXT_ID, 1);
        // commit() (not apply()) so the entry is on disk before this process can be torn down.
        // Stored as "<epoch ms>[,s]\n<text>" so reminders survive reboots.
        boolean stored = prefs.edit()
                .putInt(KEY_NEXT_ID, id + 1)
                .putBoolean(KEY_SILENT, silent)
                .putString(KEY_PREFIX + id, at + (silent ? SILENT_SUFFIX : "") + "\n" + text)
                .commit();
        if (!stored) {
            Log.e(TAG, "could not persist reminder " + id);
            return -1;
        }
        if (!setAlarm(ctx, id, at)) {
            prefs.edit().remove(KEY_PREFIX + id).commit();
            return -1;
        }
        Log.i(TAG, "scheduled #" + id + (silent ? " (silent)" : "") + " at " + at + " (in " + (at - System.currentTimeMillis()) + " ms)");
        return at;
    }

    /** Re-arms every stored reminder; alarms are wiped on reboot. Overdue ones fire right away. */
    static void rescheduleAll(Context ctx) {
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(KEY_PREFIX)) continue;
            Object value = e.getValue();
            if (!(value instanceof String)) continue;
            int id;
            try {
                id = Integer.parseInt(key.substring(KEY_PREFIX.length()));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String v = (String) value;
            int nl = v.indexOf('\n');
            if (nl <= 0) continue;
            try {
                setAlarm(ctx, id, parseAt(v.substring(0, nl)));
            } catch (NumberFormatException ignored) {
                // Corrupt entry; drop it so it cannot wedge the rest.
                prefs(ctx).edit().remove(key).commit();
            }
        }
    }

    static void fire(Context ctx, int id) {
        SharedPreferences prefs = prefs(ctx);
        String v = prefs.getString(KEY_PREFIX + id, null);
        if (v == null) {
            Log.w(TAG, "fired #" + id + " but nothing is stored for it");
            return;
        }
        // Remove before notifying so a retry can't double-post.
        prefs.edit().remove(KEY_PREFIX + id).commit();
        int nl = v.indexOf('\n');
        String text = v.substring(nl + 1);
        boolean silent = nl > 0 && v.substring(0, nl).endsWith(SILENT_SUFFIX);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        ensureChannels(ctx, nm);
        if (!nm.areNotificationsEnabled()) {
            Log.e(TAG, "fired #" + id + " but notifications are disabled for this app");
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(ctx, silent ? CHANNEL_SILENT : CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
        nm.notify(id, n);
        Log.i(TAG, "notified #" + id);
    }

    /** True when {@code notify()} would be a no-op: app notifications or the chosen channel are off. */
    static boolean notificationsBlocked(Context ctx, boolean silent) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        if (!nm.areNotificationsEnabled()) return true;
        NotificationChannel ch = nm.getNotificationChannel(silent ? CHANNEL_SILENT : CHANNEL);
        return ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * A channel's sound can't be changed by the app once created, so sound vs. silent is two
     * channels. The silent one keeps high importance so it still pops up, just without sound
     * or vibration.
     */
    static void ensureChannels(Context ctx, NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH));
        }
        if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
            NotificationChannel silent = new NotificationChannel(CHANNEL_SILENT,
                    ctx.getString(R.string.channel_silent_name), NotificationManager.IMPORTANCE_HIGH);
            silent.setSound(null, null);
            silent.enableVibration(false);
            nm.createNotificationChannel(silent);
        }
    }

    /** Parses an entry header, "<epoch ms>" or "<epoch ms>,s". */
    private static long parseAt(String header) {
        return Long.parseLong(header.endsWith(SILENT_SUFFIX)
                ? header.substring(0, header.length() - SILENT_SUFFIX.length())
                : header);
    }

    /** True when the OS will honour an exact, doze-proof alarm for this app. */
    static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    /**
     * Arms one alarm. Prefers an exact idle-proof alarm; if the OS withholds exact-alarm access
     * (Android 12/12L before the user grants it), falls back to an alarm-clock alarm, which is
     * always exact and exempt from Doze, and only as a last resort to an inexact alarm.
     */
    private static boolean setAlarm(Context ctx, int id, long at) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return false;

        Intent i = new Intent(ctx, ReminderReceiver.class).putExtra(EXTRA_ID, id);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        if (canScheduleExact(ctx)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return true;
            } catch (SecurityException e) {
                Log.w(TAG, "exact alarm refused for #" + id, e);
            }
        }

        try {
            PendingIntent show = PendingIntent.getActivity(
                    ctx, id, new Intent(ctx, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            Log.i(TAG, "#" + id + " using alarm-clock fallback");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "alarm clock refused for #" + id, e);
        }

        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(TAG, "#" + id + " using inexact fallback");
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "could not arm alarm for #" + id, e);
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
