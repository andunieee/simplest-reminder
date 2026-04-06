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
 * The whole UI: a text box, the preset delays and OK. Built in code with framework widgets only
 * (no layout inflation, no support libraries) to keep cold start as fast as possible.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1;

    private EditText input;
    private Button[] presetButtons;
    private int selected;

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
        bar.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // Edge-to-edge is enforced on targetSdk 35+: keep content clear of system bars and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets in = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(in.left + pad, in.top + pad, in.right + pad, in.bottom + pad / 2);
            return WindowInsets.CONSUMED;
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

    private void submit() {
        if (input.getText().toString().trim().isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        schedule();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_NOTIFICATIONS) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notifications are off — reminders can't be shown", Toast.LENGTH_LONG).show();
        }
        schedule();
    }

    private void schedule() {
        int minutes = Reminders.PRESETS_MIN[selected];
        Reminders.schedule(this, input.getText().toString().trim(), minutes);
        Toast.makeText(this, "Reminder in " + Reminders.label(minutes), Toast.LENGTH_SHORT).show();
        input.setText("");
        finishAndRemoveTask();
    }
}
