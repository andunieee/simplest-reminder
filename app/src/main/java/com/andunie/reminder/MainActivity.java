package com.andunie.reminder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The whole UI: a text box, a sound toggle, the preset delays and OK. Built in code with framework widgets only
 * (no layout inflation, no support libraries) to keep cold start as fast as possible.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1;

    private EditText input;
    private Button[] presetButtons;
    private Button soundButton;
    private int selected;
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
            submit();
            return true;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Per-reminder sound choice; starts from whatever the last reminder used.
        soundButton = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
        soundButton.setOnClickListener(v -> setSilent(!silent));
        bar.addView(soundButton, new LinearLayout.LayoutParams(-2, -2));
        setSilent(Reminders.lastSilent(this));
        presetButtons = new Button[Reminders.PRESETS_MIN.length];
        for (int i = 0; i < presetButtons.length; i++) {
            Button b = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
            b.setText(Reminders.label(Reminders.PRESETS_MIN[i]));
            int idx = i;
            b.setOnClickListener(v -> select(idx));
            bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            presetButtons[i] = b;
        }
        Button ok = new Button(this, null, 0, android.R.style.Widget_Material_Button_Colored);
        ok.setText(R.string.ok);
        ok.setOnClickListener(v -> submit());
        // Long-press: 5-second self-test of the alarm path (no need to wait 10 minutes).
        ok.setOnLongClickListener(v -> testAlarm());
        bar.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));

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

    /** Launcher shortcuts pass the preset as a "minutes" extra. */
    private void applyIntent(Intent intent) {
        int minutes = Reminders.PRESETS_MIN[0];
        Bundle extras = intent.getExtras();
        Object m = extras == null ? null : extras.get("minutes");
        if (m instanceof Integer) minutes = (Integer) m;
        else if (m instanceof String) minutes = Integer.parseInt((String) m);
        int idx = 0;
        for (int i = 0; i < Reminders.PRESETS_MIN.length; i++) {
            if (Reminders.PRESETS_MIN[i] == minutes) idx = i;
        }
        select(idx);
    }

    private void select(int idx) {
        selected = idx;
        for (int i = 0; i < presetButtons.length; i++) {
            presetButtons[i].setAlpha(i == idx ? 1f : 0.4f);
        }
    }

    private void setSilent(boolean s) {
        silent = s;
        soundButton.setText(s ? "\uD83D\uDD15" : "\uD83D\uDD14"); // 🔕 / 🔔
        soundButton.setContentDescription(getString(s ? R.string.sound_off : R.string.sound_on));
    }

    private void submit() {
        if (input.getText().toString().trim().isEmpty()) return;
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
        int minutes = Reminders.PRESETS_MIN[selected];
        long at = Reminders.schedule(this, input.getText().toString().trim(), minutes, silent);
        if (at < 0) {
            Toast.makeText(this, "Couldn't schedule the reminder", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        String time = android.text.format.DateFormat.getTimeFormat(this)
                .format(new java.util.Date(at));
        Toast.makeText(this, "Reminder at " + time, Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }

    /** Hidden self-test: same code path, five seconds out. Long-press the OK button. */
    private boolean testAlarm() {
        warnIfBlocked();
        String text = input.getText().toString().trim();
        if (text.isEmpty()) text = "Test reminder";
        long at = Reminders.scheduleIn(this, text, 5_000L, silent);
        if (at < 0) {
            Toast.makeText(this, "Couldn't schedule the reminder", Toast.LENGTH_LONG).show();
            return true;
        }
        input.setText("");
        Toast.makeText(this, "Test reminder in 5 s", Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
        return true;
    }
}
