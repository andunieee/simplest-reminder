# Simplest Reminder

<img src="art/logo.svg" width="96" alt="Simplest Reminder logo">

A single text box. Type, pick 10 min / 30 min / 1 hour, optionally tap 🔔 to make it silent (🔕),
press OK. The app closes immediately; an `AlarmManager` alarm wakes the device later and posts a
notification.

- **Long-press OK** to set a custom time: a delay (`45`, `45m`, `1h30`, `2h 15m`, `in 3 days`,
  `5s`) or a clock time (`14:30`, `9am`, `9:15 pm`, `at 7`), or tap **Clock** for a time picker.
  A clock time means its next occurrence; on a 12-hour phone `9:30` without am/pm is whichever of
  9:30 and 21:30 comes first. A bare number is minutes.
- **Long-press a preset** to replace its delay (`45m`, `2h`, `1h30`). It's remembered.

## Design

- **No process is kept alive.** `Reminders.schedule()` persists the reminder and arms an
  `AlarmManager` alarm, then the activity finishes with `finishAndRemoveTask()`.
- Reminders are stored in `SharedPreferences` (`reminders`, key `r:<id>` → `"<epochMs>[,s]\n<text>"`,
  `,s` marking a silent one) using `commit()`, so the entry is on disk before the process can be
  torn down. The last sound choice is kept under `silent` and becomes the next default; edited
  presets are kept under `presets` as comma-separated minutes.
- Launcher shortcuts are static, so they keep their 10 min / 30 min / 1 hour delays even after a
  preset is edited.
- Sound is per reminder via two channels, `reminders` and `reminders_silent` (no sound, no
  vibration, still heads-up), since an app can't change a channel's sound after creating it.
- Exact alarms: `setExactAndAllowWhileIdle()` when the OS allows it (always on API 26–30, and on
  31+ with `USE_EXACT_ALARM` / granted `SCHEDULE_EXACT_ALARM`). If exact alarms are unavailable
  (Android 12/12L before the user grants them), it falls back to `setAlarmClock()`, which is
  always exact and Doze-exempt, and finally to `setAndAllowWhileIdle()`.
- `BootReceiver` re-arms every stored reminder after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- The alarm receiver posts the notification; the app does not need to be running or in recents.

## Build & install

Needs [`just`](https://github.com/casey/just) and `adb`. Every recipe that installs targets **all**
connected devices.

```sh
just install        # build the release APK and install it on every connected phone
just install-only   # install the already-built APK without rebuilding
just run            # install, then open the app on every phone
just uninstall      # remove it from every phone
just logs [serial]  # adb logcat -s SimpleReminder
just alarms         # pending alarms for the app on every phone
```

The release build is signed with the debug key so it installs as-is
(`app/build/outputs/apk/release/app-release.apk`).

## Releases

Pushing a tag starting with `v` (for example `v1.2`) builds the release APK on GitHub Actions and
publishes it as an asset on that tag's release.

## Verify

Long-press OK and enter `5s` to schedule a reminder 5 seconds out through the same alarm path,
then check `just logs`: "scheduled #1 at ...", "alarm delivered #1", "notified #1".

If `dumpsys alarm` lists the alarm but no notification appears, the alarm path works and the
notification is being blocked (app-level notifications off, or the `reminders` /
`reminders_silent` channel disabled in system settings). `SimpleReminder` logs that case too: "notifications are disabled for this app".

Force-stopping an app (`Settings > Apps > Force stop`, or some OEM "clear all" actions) cancels
all of its alarms; nothing a regular app can do survives that. The boot/update receiver re-arms
reminders afterwards.
