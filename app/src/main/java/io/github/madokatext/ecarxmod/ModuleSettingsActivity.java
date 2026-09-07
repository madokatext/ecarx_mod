package io.github.madokatext.ecarxmod;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Native Android 9 settings screen, accessible from the launcher and LSPosed. */
public final class ModuleSettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        Bundle saved = state == null ? BootSettings.readLocal(this) : state;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        body.addView(text(getString(R.string.boot_settings_title), 28));
        body.addView(text(getString(R.string.boot_settings_intro), 18));

        Switch enabled = new Switch(this);
        enabled.setId(1001);
        enabled.setText(R.string.force_hev_label);
        enabled.setTextSize(22);
        enabled.setMinHeight(dp(72));
        enabled.setChecked(saved.getBoolean(BootSettings.ENABLED));
        body.addView(enabled, matchWidth());

        TextView delayLabel = text("", 22);
        body.addView(delayLabel);
        SeekBar delay = new SeekBar(this);
        delay.setId(1002);
        delay.setMax(BootSettings.MAX_DELAY - BootSettings.MIN_DELAY);
        // ProgressBar.setMinHeight is API 29; use the View method available on Android 9.
        delay.setMinimumHeight(dp(64));
        delay.setProgress(BootSettings.clampDelay(saved.getInt(BootSettings.DELAY)) - BootSettings.MIN_DELAY);
        Runnable showDelay = () -> delayLabel.setText(getString(R.string.boot_delay_label,
                delay.getProgress() + BootSettings.MIN_DELAY));
        delay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                showDelay.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        showDelay.run();
        body.addView(delay, matchWidth());
        body.addView(text(getString(R.string.boot_delay_hint), 18));

        LinearLayout presets = new LinearLayout(this);
        for (int value : new int[]{5, 30, 60, 180, 300}) {
            Button preset = new Button(this);
            preset.setText(getString(R.string.seconds_short, value));
            preset.setOnClickListener(view -> delay.setProgress(value - BootSettings.MIN_DELAY));
            presets.addView(preset, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        body.addView(presets, matchWidth());

        Button save = new Button(this);
        save.setText(R.string.save_boot_settings);
        save.setTextSize(20);
        LinearLayout.LayoutParams saveLayout = matchWidth();
        saveLayout.height = dp(64);
        saveLayout.topMargin = dp(16);
        body.addView(save, saveLayout);
        save.setOnClickListener(view -> {
            boolean success = BootSettings.preferences(this).edit()
                    .putBoolean(BootSettings.ENABLED, enabled.isChecked())
                    .putInt(BootSettings.DELAY, delay.getProgress() + BootSettings.MIN_DELAY).commit();
            Toast.makeText(this, success ? R.string.boot_settings_saved : R.string.boot_settings_save_failed,
                    Toast.LENGTH_LONG).show();
        });
        body.addView(text(getString(R.string.boot_sync_hint), 18));
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        Switch enabled = findViewById(1001);
        SeekBar delay = findViewById(1002);
        state.putBoolean(BootSettings.ENABLED, enabled.isChecked());
        state.putInt(BootSettings.DELAY, delay.getProgress() + BootSettings.MIN_DELAY);
        super.onSaveInstanceState(state);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
