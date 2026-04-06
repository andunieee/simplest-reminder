package com.andunie.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Reminders.fire(ctx, intent.getIntExtra(Reminders.EXTRA_ID, -1));
    }
}
