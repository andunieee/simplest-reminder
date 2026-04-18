# Remind

A single text box. Type, pick 10 min / 30 min / 1 hour, press OK. The app closes immediately;
an `AlarmManager` alarm wakes the device later and posts a notification.

## Design

- **No process is kept alive.** `Reminders.schedule()` persists the reminder and arms an
  `AlarmManager` alarm, then the activity finishes with `finishAndRemoveTask()`.
- Reminders are stored in `SharedPreferences` (`reminders`, key `r:<id>` → `"<epochMs>\n<text>"`)
  using `commit()`, so the entry is on disk before the process can be torn down.
- Exact alarms: `setExactAndAllowWhileIdle()` when the OS allows it (always on API 26–30, and on
  31+ with `USE_EXACT_ALARM` / granted `SCHEDULE_EXACT_ALARM`). If exact alarms are unavailable
  (Android 12/12L before the user grants them), it falls back to `setAlarmClock()`, which is
  always exact and Doze-exempt, and finally to `setAndAllowWhileIdle()`.
- `BootReceiver` re-arms every stored reminder after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- The alarm receiver posts the notification; the app does not need to be running or in recents.

## Build

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs as-is.

## Install / verify

```sh
adb install -r app/build/outputs/apk/release/app-release.apk

# quick check without waiting 10 minutes: long-press OK -> fires in 5 s
adb logcat -s Remind           # "scheduled #1 at ...", "alarm delivered #1", "notified #1"
adb shell dumpsys alarm | grep -i andunie
```

If `dumpsys alarm` lists the alarm but no notification appears, the alarm path works and the
notification is being blocked (app-level notifications off, or the `reminders` channel disabled
in system settings). `Remind` logs that case too: "notifications are disabled for this app".

Force-stopping an app (`Settings > Apps > Force stop`, or some OEM "clear all" actions) cancels
all of its alarms; nothing a regular app can do survives that. The boot/update receiver re-arms
reminders afterwards.
