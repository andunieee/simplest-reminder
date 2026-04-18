package com.andunie.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Runs when an {@code AlarmManager} alarm fires, even if the app process was killed. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int id = intent.getIntExtra(Reminders.EXTRA_ID, -1);
        Log.i("Remind", "alarm delivered #" + id);
        if (id < 0) return;
        try {
            Reminders.fire(ctx, id);
        } catch (RuntimeException e) {
            Log.e("Remind", "could not post reminder #" + id, e);
        }
    }
}
