package com.andunie.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Alarms do not survive a reboot or an app update, so re-arm the stored ones. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Log.i("Remind", "re-arming alarms after " + a);
            Reminders.rescheduleAll(ctx);
        }
    }
}
