package io.github.madokatext.ecarxmod;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Native Android 9 settings screen, accessible from the launcher and LSPosed. */
public final class ModuleSettingsActivity extends Activity {
    private static final int POWER_MODE_GROUP = 1001;
    private static final int POWER_MODE_OFF = 1010;
    private static final int POWER_MODE_EV = 1011;
    private static final int POWER_MODE_HEV = 1012;
    private TextView mediaStatus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshMedia = new Runnable() {
        @Override public void run() {
            if (mediaStatus == null) return;
            Bundle status = MediaBridgeState.status();
            long updated = status.getLong("sender_updated");
            String age = updated == 0 ? "" : "\n宿主状态更新于 "
                    + Math.max(0, (SystemClock.elapsedRealtime() - updated) / 1000) + " 秒前";
            mediaStatus.setText("通知使用权：" + (notificationAccess() ? "已开启" : "未开启")
                    + "\n" + status.getString("sender_status") + age
                    + "\n" + status.getString("reader_status"));
            main.postDelayed(this, 1500);
        }
    };

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

        body.addView(text(getString(R.string.boot_power_mode_label), 22));
        RadioGroup powerMode = new RadioGroup(this);
        powerMode.setId(POWER_MODE_GROUP);
        powerMode.setOrientation(LinearLayout.VERTICAL);
        addPowerMode(powerMode, POWER_MODE_OFF, R.string.boot_power_mode_off);
        addPowerMode(powerMode, POWER_MODE_EV, R.string.boot_power_mode_ev);
        addPowerMode(powerMode, POWER_MODE_HEV, R.string.boot_power_mode_hev);
        int savedMode = BootSettings.powerMode(saved);
        powerMode.check(savedMode == BootSettings.MODE_EV ? POWER_MODE_EV
                : savedMode == BootSettings.MODE_HEV ? POWER_MODE_HEV : POWER_MODE_OFF);
        body.addView(powerMode, matchWidth());
        body.addView(text(getString(R.string.boot_power_mode_hint), 18));

        Switch disableLowSpeed = new Switch(this);
        disableLowSpeed.setId(1003);
        disableLowSpeed.setText(R.string.disable_low_speed_label);
        disableLowSpeed.setTextSize(22);
        disableLowSpeed.setMinimumHeight(dp(72));
        disableLowSpeed.setChecked(saved.getBoolean(BootSettings.DISABLE_LOW_SPEED, true));
        body.addView(disableLowSpeed, matchWidth());

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

        body.addView(text(getString(R.string.hvac_settings_title), 26));
        Switch hvacCorrection = new Switch(this);
        hvacCorrection.setId(1004);
        hvacCorrection.setText(R.string.hvac_correction_label);
        hvacCorrection.setTextSize(22);
        hvacCorrection.setMinimumHeight(dp(72));
        hvacCorrection.setChecked(saved.getBoolean(BootSettings.HVAC_CORRECTION, false));
        body.addView(hvacCorrection, matchWidth());
        body.addView(text(getString(R.string.hvac_correction_hint), 18));

        body.addView(text(getString(R.string.media_settings_title), 26));
        Switch mediaForward = new Switch(this);
        mediaForward.setId(1005);
        mediaForward.setText(R.string.media_forward_label);
        mediaForward.setTextSize(22);
        mediaForward.setMinimumHeight(dp(72));
        mediaForward.setChecked(saved.getBoolean(BootSettings.MEDIA_FORWARD, false));
        body.addView(mediaForward, matchWidth());
        body.addView(text(getString(R.string.media_forward_hint), 18));
        Switch mediaDebug = new Switch(this);
        mediaDebug.setId(1006);
        mediaDebug.setText(R.string.media_debug_label);
        mediaDebug.setTextSize(22);
        mediaDebug.setMinimumHeight(dp(64));
        mediaDebug.setChecked(saved.getBoolean(BootSettings.MEDIA_DEBUG, true));
        body.addView(mediaDebug, matchWidth());
        Button notification = new Button(this);
        notification.setText(R.string.media_notification_access);
        notification.setOnClickListener(view -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Exception error) {
                MediaBridgeState.error("[settings] notification access screen unavailable", error);
                Toast.makeText(this, R.string.media_notification_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        body.addView(notification, matchWidth());
        mediaStatus = text("", 17);
        mediaStatus.setTextIsSelectable(true);
        body.addView(mediaStatus, matchWidth());

        Button save = new Button(this);
        save.setText(R.string.save_boot_settings);
        save.setTextSize(20);
        LinearLayout.LayoutParams saveLayout = matchWidth();
        saveLayout.height = dp(64);
        saveLayout.topMargin = dp(16);
        body.addView(save, saveLayout);
        save.setOnClickListener(view -> {
            int selectedMode = selectedPowerMode(powerMode);
            boolean success = BootSettings.preferences(this).edit()
                    .putInt(BootSettings.POWER_MODE, selectedMode)
                    .putBoolean(BootSettings.ENABLED, selectedMode == BootSettings.MODE_HEV)
                    .putBoolean(BootSettings.DISABLE_LOW_SPEED, disableLowSpeed.isChecked())
                    .putBoolean(BootSettings.HVAC_CORRECTION, hvacCorrection.isChecked())
                    .putBoolean(BootSettings.MEDIA_FORWARD, mediaForward.isChecked())
                    .putBoolean(BootSettings.MEDIA_DEBUG, mediaDebug.isChecked())
                    .putInt(BootSettings.DELAY, delay.getProgress() + BootSettings.MIN_DELAY).commit();
            if (success) {
                MediaBridgeState.info("[settings] saved mediaForward=" + mediaForward.isChecked()
                        + " mediaDebug=" + mediaDebug.isChecked());
                if (mediaForward.isChecked() && notificationAccess()) {
                    try { NotificationListenerService.requestRebind(new ComponentName(this, MediaNotificationListener.class)); }
                    catch (Exception error) { MediaBridgeState.error("[settings] listener rebind failed", error); }
                }
            }
            Toast.makeText(this, success ? R.string.boot_settings_saved : R.string.boot_settings_save_failed,
                    Toast.LENGTH_LONG).show();
        });
        body.addView(text(getString(R.string.boot_sync_hint), 18));
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        RadioGroup powerMode = findViewById(POWER_MODE_GROUP);
        SeekBar delay = findViewById(1002);
        Switch disableLowSpeed = findViewById(1003);
        Switch hvacCorrection = findViewById(1004);
        Switch mediaForward = findViewById(1005);
        Switch mediaDebug = findViewById(1006);
        state.putInt(BootSettings.POWER_MODE, selectedPowerMode(powerMode));
        state.putBoolean(BootSettings.DISABLE_LOW_SPEED, disableLowSpeed.isChecked());
        state.putBoolean(BootSettings.HVAC_CORRECTION, hvacCorrection.isChecked());
        state.putBoolean(BootSettings.MEDIA_FORWARD, mediaForward.isChecked());
        state.putBoolean(BootSettings.MEDIA_DEBUG, mediaDebug.isChecked());
        state.putInt(BootSettings.DELAY, delay.getProgress() + BootSettings.MIN_DELAY);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(refreshMedia);
        main.post(refreshMedia);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refreshMedia);
        super.onPause();
    }

    private boolean notificationAccess() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(this, MediaNotificationListener.class));
    }

    private void addPowerMode(RadioGroup group, int id, int label) {
        RadioButton option = new RadioButton(this);
        option.setId(id);
        option.setText(label);
        option.setTextSize(22);
        option.setMinimumHeight(dp(64));
        group.addView(option, matchWidth());
    }

    private int selectedPowerMode(RadioGroup group) {
        int selected = group.getCheckedRadioButtonId();
        if (selected == POWER_MODE_EV) return BootSettings.MODE_EV;
        if (selected == POWER_MODE_HEV) return BootSettings.MODE_HEV;
        return BootSettings.MODE_DISABLED;
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
