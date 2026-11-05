package com.andunie.reminder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/**
 * The whole UI: a text box, a sound toggle, the preset delays and OK. Long-pressing OK picks a
 * custom time; long-pressing a preset replaces its delay. Built in code with framework widgets only
 * (no layout inflation, no support libraries) to keep cold start as fast as possible.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1;

    private EditText input;
    private Button[] presetButtons;
    private Button soundButton;
    private int[] presets;
    /** The delay OK schedules with; normally one of the presets, but a shortcut may ask for another. */
    private int minutes;
    /** A custom time picked by long-pressing OK, waiting on the permission prompt; 0 when none. */
    private long pendingAt;
    private boolean silent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        input = new EditText(this);
        input.setHint(R.string.hint);
        input.setTextSize(22);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(null);
        // Wrapping text but with a "Done" key instead of newline.
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(Integer.MAX_VALUE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit(0);
            return true;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Per-reminder sound choice; starts from whatever the last reminder used.
        soundButton = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
        soundButton.setOnClickListener(v -> setSilent(!silent));
        bar.addView(soundButton, new LinearLayout.LayoutParams(-2, -2));
        setSilent(Reminders.lastSilent(this));
        presets = Reminders.presets(this);
        presetButtons = new Button[presets.length];
        for (int i = 0; i < presetButtons.length; i++) {
            Button b = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
            b.setText(Reminders.label(presets[i]));
            int idx = i;
            b.setOnClickListener(v -> select(presets[idx]));
            b.setOnLongClickListener(v -> {
                editPreset(idx);
                return true;
            });
            bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            presetButtons[i] = b;
        }
        Button ok = new Button(this, null, 0, android.R.style.Widget_Material_Button_Colored);
        ok.setText(R.string.ok);
        ok.setOnClickListener(v -> submit(0));
        ok.setOnLongClickListener(v -> {
            pickCustomTime();
            return true;
        });
        bar.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));
        // Nothing to remind about yet: OK (tap and long-press) is off until there's text.
        ok.setEnabled(false);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                ok.setEnabled(s.toString().trim().length() > 0);
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // Edge-to-edge is enforced on targetSdk 35+: keep content clear of system bars and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(in.left + pad, in.top + pad, in.right + pad, in.bottom + pad / 2);
                return WindowInsets.CONSUMED;
            }
            // API 26-29: WindowInsets.Type doesn't exist; system window insets include the IME.
            v.setPadding(insets.getSystemWindowInsetLeft() + pad, insets.getSystemWindowInsetTop() + pad,
                    insets.getSystemWindowInsetRight() + pad, insets.getSystemWindowInsetBottom() + pad / 2);
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);

        applyIntent(getIntent());
        input.requestFocus();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().show(WindowInsets.Type.ime());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyIntent(intent);
    }

    /**
     * Launcher shortcuts pass a delay as a "minutes" extra. It's honoured even when no preset
     * matches it any more, since the shortcut's label promises that delay.
     */
    private void applyIntent(Intent intent) {
        int m = presets[0];
        Bundle extras = intent.getExtras();
        Object extra = extras == null ? null : extras.get("minutes");
        if (extra instanceof Integer) m = (Integer) extra;
        else if (extra instanceof String) m = Integer.parseInt((String) extra);
        select(m);
    }

    /** Uses this delay for OK and highlights the presets that match it. */
    private void select(int m) {
        minutes = m;
        for (int i = 0; i < presetButtons.length; i++) {
            presetButtons[i].setAlpha(presets[i] == m ? 1f : 0.4f);
        }
    }

    /** Long-press on a preset: type a new delay for it. It's kept for future reminders. */
    private void editPreset(int idx) {
        askTime(getString(R.string.edit_preset), getString(R.string.edit_preset_hint),
                text -> {
                    long delay = Times.parseDelay(text);
                    long m = Math.round(delay / 60_000.0);
                    return delay < 0 || m < 1 ? -1 : m;
                },
                m -> Reminders.label((int) m),
                null,
                m -> {
                    presets[idx] = (int) m;
                    Reminders.setPreset(this, idx, (int) m);
                    presetButtons[idx].setText(Reminders.label((int) m));
                    select((int) m);
                });
    }

    /** Long-press on OK: schedule at a typed delay or clock time, or one picked on a clock face. */
    private void pickCustomTime() {
        boolean is24h = DateFormat.is24HourFormat(this);
        askTime(getString(R.string.custom_time), getString(R.string.custom_time_hint),
                text -> Times.parse(text, System.currentTimeMillis(), is24h),
                this::describe,
                () -> {
                    Calendar now = Calendar.getInstance();
                    new TimePickerDialog(this,
                            (view, hour, minute) -> submit(Times.next(System.currentTimeMillis(), hour, minute)),
                            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), is24h).show();
                },
                this::submit);
    }

    /**
     * A one-line text dialog that previews what's typed and enables OK only once it parses.
     *
     * @param parse maps the text to a value, or -1 if it isn't understood
     * @param preview describes a parsed value under the field
     * @param onClock if not null, adds a button that opens a clock face instead
     */
    private void askTime(String title, String hint, ToLongFunction<String> parse,
            LongFunction<String> preview, Runnable onClock, LongConsumer onPick) {
        float dp = getResources().getDisplayMetrics().density;
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        TextView hintText = new TextView(this);
        hintText.setPadding((int) (4 * dp), 0, 0, 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        box.addView(field);
        box.addView(hintText);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    long value = parse.applyAsLong(field.getText().toString());
                    if (value >= 0) onPick.accept(value);
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (onClock != null) builder.setNeutralButton(R.string.pick_clock, (d, w) -> onClock.run());
        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();

        Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Runnable update = () -> {
            long value = parse.applyAsLong(field.getText().toString());
            ok.setEnabled(value >= 0);
            hintText.setText(value >= 0 ? preview.apply(value) : "");
        };
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { update.run(); }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (ok.isEnabled()) ok.performClick();
            return true;
        });
        update.run();
        field.requestFocus();
    }

    /** "14:30 · in 2h 5m", with the day when it isn't today. */
    private String describe(long at) {
        return when(at) + " · in " + Times.duration(at - System.currentTimeMillis());
    }

    private String when(long at) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(at)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL;
        }
        return DateUtils.formatDateTime(this, at, flags);
    }

    private void setSilent(boolean s) {
        silent = s;
        soundButton.setText(s ? "\uD83D\uDD15" : "\uD83D\uDD14"); // 🔕 / 🔔
        soundButton.setContentDescription(getString(s ? R.string.sound_off : R.string.sound_on));
    }

    /** Schedules with the selected delay, or at {@code at} if it's non-zero. */
    private void submit(long at) {
        if (input.getText().toString().trim().isEmpty()) return;
        pendingAt = at;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        warnIfBlocked();
        schedule();
    }

    /**
     * A blocked channel or a disabled app-level switch makes {@code notify()} a silent no-op, which
     * looks exactly like "the reminder never fired". Say so instead of failing quietly.
     */
    private void warnIfBlocked() {
        if (Reminders.notificationsBlocked(this, silent)) {
            Toast.makeText(this, "Notifications are off — reminders won't be shown",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_NOTIFICATIONS) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notifications are off — reminders won't be shown",
                    Toast.LENGTH_LONG).show();
        }
        schedule();
    }

    private void schedule() {
        String text = input.getText().toString().trim();
        long at = pendingAt != 0
                ? Reminders.scheduleAt(this, text, pendingAt, silent)
                : Reminders.schedule(this, text, minutes, silent);
        if (at < 0) {
            Toast.makeText(this, "Couldn't schedule the reminder", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        Toast.makeText(this, "Reminder at " + when(at), Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }
}
