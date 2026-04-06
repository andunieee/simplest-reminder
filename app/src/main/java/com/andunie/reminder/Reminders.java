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

import java.util.Map;

/** Scheduling, persistence and display of reminders. */
final class Reminders {
    /** Preset delays in minutes, in display order. Keep in sync with res/xml/shortcuts.xml. */
    static final int[] PRESETS_MIN = {10, 30, 60};

    private static final String PREFS = "reminders";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_PREFIX = "r:";
    private static final String CHANNEL = "reminders";
    static final String EXTRA_ID = "id";

    private Reminders() {}

    static String label(int minutes) {
        return minutes < 60 ? minutes + " min" : (minutes / 60) + (minutes == 60 ? " hour" : " hours");
    }

    static void schedule(Context ctx, String text, int minutes) {
        SharedPreferences prefs = prefs(ctx);
        int id = prefs.getInt(KEY_NEXT_ID, 1);
        long at = System.currentTimeMillis() + minutes * 60_000L;
        // Stored as "<epoch ms>\n<text>" so reminders survive reboots.
        prefs.edit()
                .putInt(KEY_NEXT_ID, id + 1)
                .putString(KEY_PREFIX + id, at + "\n" + text)
                .apply();
        setAlarm(ctx, id, at);
    }

    /** Re-arms every stored reminder; alarms are wiped on reboot. Overdue ones fire right away. */
    static void rescheduleAll(Context ctx) {
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (!e.getKey().startsWith(KEY_PREFIX)) continue;
            int id = Integer.parseInt(e.getKey().substring(KEY_PREFIX.length()));
            String v = (String) e.getValue();
            setAlarm(ctx, id, Long.parseLong(v.substring(0, v.indexOf('\n'))));
        }
    }

    static void fire(Context ctx, int id) {
        SharedPreferences prefs = prefs(ctx);
        String v = prefs.getString(KEY_PREFIX + id, null);
        if (v == null) return;
        prefs.edit().remove(KEY_PREFIX + id).apply();
        String text = v.substring(v.indexOf('\n') + 1);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        ensureChannel(ctx, nm);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
        nm.notify(id, n);
    }

    static void ensureChannel(Context ctx, NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH));
    }

    private static void setAlarm(Context ctx, int id, long at) {
        Intent i = new Intent(ctx, ReminderReceiver.class).putExtra(EXTRA_ID, id);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
