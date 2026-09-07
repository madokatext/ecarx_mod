package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import de.robv.android.xposed.XposedHelpers;

/** Delays boot restoration and confirms HEV once per OS boot before allowing charge writes. */
final class BootHevGate {
    enum Result { WAIT, READY, STOP }

    private static final String TAG = "EcarxSocFix";
    private static final String BOOT_ID = "hev_boot_id";
    private static final String STATUS = "hev_boot_status";
    private static final String STARTED = "hev_boot_started";
    private static final String ENABLED = "hev_boot_enabled";
    private static final String DELAY = "hev_boot_delay";
    private static final String WRITES = "hev_boot_writes";
    private static final String LAST_WRITE = "hev_boot_last_write";
    private static final String WAITING = "waiting";
    private static final String DONE = "done";
    private static final String CANCELLED = "cancelled";
    private static final String FAILED = "failed";
    private static final int HEV = 0x22040d01;
    private static final int EV = 0x22040d02;
    private static final int SAVE = 0x22040d03;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable wake;
    private final BootLowSpeedAction lowSpeed;
    private HandlerThread loaderThread;
    private Handler loader;
    private Context context;
    private SharedPreferences state;
    private volatile boolean stopped;
    private boolean configured;
    private boolean cancelled;
    private boolean enabled;
    private String status = WAITING;
    private long startedAt;
    private long dueAt;
    private long lastWriteAt;
    private long waitMs = 500;
    private int loadAttempts;
    private int writes;
    private int lastSynced = -1;

    BootHevGate(Runnable wake, BootLowSpeedAction lowSpeed) {
        this.wake = wake;
        this.lowSpeed = lowSpeed;
    }

    void attach(Context host) {
        if (context != null || stopped) return;
        context = host;
        startedAt = SystemClock.elapsedRealtime();
        state = host.getSharedPreferences("ecarx_soc_fix", Context.MODE_PRIVATE);
        loaderThread = new HandlerThread("EcarxBootSettings");
        loaderThread.start();
        loader = new Handler(loaderThread.getLooper());
        loader.post(this::load);
    }

    private void load() {
        if (stopped) return;
        try {
            String id = bootId();
            Bundle settings = null;
            if (!id.equals(state.getString(BOOT_ID, ""))) {
                // Binder/provider startup must not block the settings app's main thread.
                settings = context.getContentResolver().call(BootSettings.URI, BootSettings.READ, null, null);
                if (settings == null || !settings.containsKey(BootSettings.ENABLED)
                        || !settings.containsKey(BootSettings.DISABLE_LOW_SPEED)
                        || !settings.containsKey(BootSettings.DELAY)) {
                    throw new IllegalStateException("Module boot settings are unavailable");
                }
            }
            Bundle loaded = settings;
            main.post(() -> initialize(id, loaded));
            loaderThread.quitSafely();
        } catch (Throwable error) {
            if (++loadAttempts < 5 && !stopped) {
                loader.postDelayed(this::load, 1000);
            } else {
                main.post(() -> {
                    if (stopped) return;
                    configured = true;
                    status = FAILED;
                    BatterySocHook.logFailure("Skipped boot restore: settings could not be loaded", error);
                    wake.run();
                });
                loaderThread.quitSafely();
            }
        }
    }

    private String bootId() throws Exception {
        int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (count >= 0) return "count:" + count;
        String id = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),
                StandardCharsets.US_ASCII).trim();
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalStateException("No OS boot identifier");
        return "kernel:" + id;
    }

    private void initialize(String id, Bundle settings) {
        if (stopped) return;
        try {
            if (settings != null) {
                SharedPreferences.Editor editor = state.edit().putString(BOOT_ID, id)
                        .putString(STATUS, cancelled ? CANCELLED : WAITING)
                        .putBoolean(ENABLED, settings.getBoolean(BootSettings.ENABLED))
                        .putInt(DELAY, BootSettings.clampDelay(settings.getInt(BootSettings.DELAY)))
                        .putLong(STARTED, startedAt).putInt(WRITES, 0).putLong(LAST_WRITE, 0);
                lowSpeed.initializeForBoot(editor, settings.getBoolean(BootSettings.DISABLE_LOW_SPEED));
                commit(editor);
            }
            enabled = state.getBoolean(ENABLED, true);
            dueAt = state.getLong(STARTED, startedAt)
                    + BootSettings.clampDelay(state.getInt(DELAY, BootSettings.MIN_DELAY)) * 1000L;
            writes = Math.max(0, state.getInt(WRITES, 0));
            lastWriteAt = state.getLong(LAST_WRITE, 0);
            status = state.getString(STATUS, FAILED);
            if (cancelled) {
                status = CANCELLED;
                commit(state.edit().putString(STATUS, status));
            }
            configured = true;
            Log.i(TAG, "Boot HEV enabled=" + enabled + ", status=" + status
                    + ", remaining delay=" + Math.max(0, dueAt - SystemClock.elapsedRealtime()) + " ms");
        } catch (Throwable error) {
            configured = true;
            fail("could not persist boot sequence", error);
        }
        wake.run();
    }

    // Called by BootStateRestorer with its own-write ThreadLocal set, so HEV requests cannot
    // be mistaken for manual edits. All state below is confined to the host's main looper.
    Result step(Object manager) {
        waitMs = 1000;
        if (stopped) return Result.STOP;
        if (cancelled) return Result.READY;
        if (!configured) return Result.WAIT;
        if (DONE.equals(status) || CANCELLED.equals(status)) return Result.READY;
        if (!WAITING.equals(status)) return Result.STOP;
        long now = SystemClock.elapsedRealtime();
        if (now < dueAt) {
            waitMs = dueAt - now;
            return Result.WAIT;
        }
        try {
            if (!enabled) {
                complete();
                return Result.READY;
            }
            if (!BatterySocHook.isCarReady(manager)) return waitForVehicle(now);
            int ignition = (Integer) XposedHelpers.callMethod(manager, "getSensorEvent",
                    new Class<?>[]{int.class}, 2097408);
            if (ignition != 2097413 && ignition != 2097414 && ignition != 2097415) {
                return waitForVehicle(now);
            }
            int actual = (Integer) XposedHelpers.callMethod(manager, "getFunctionValue",
                    new Class<?>[]{int.class}, VehicleControl.EV_HEV.function);
            syncActual(manager, actual);
            if (writes > 0 && now < lastWriteAt + 1000) {
                waitMs = lastWriteAt + 1000 - now;
                return Result.WAIT;
            }
            if (actual == HEV) {
                complete();
                Log.i(TAG, "Boot HEV readback matched; continuing charge restore");
                return Result.READY;
            }
            if (writes >= 2) return fail("HEV not confirmed after two requests", null);
            if (actual != EV && actual != SAVE) return waitForVehicle(now);
            if (now >= dueAt + 60000) return fail("vehicle readiness timed out", null);

            // Persist the budget before sending; process restarts cannot reset the retry count.
            commit(state.edit().putInt(WRITES, writes + 1).putLong(LAST_WRITE, now));
            writes++;
            lastWriteAt = now;
            XposedHelpers.callMethod(manager, "setFunctionValue", new Class<?>[]{int.class, int.class},
                    VehicleControl.EV_HEV.function, HEV);
            Log.i(TAG, "Boot HEV request " + writes + "/2");
            return Result.WAIT;
        } catch (Throwable error) {
            BatterySocHook.logFailure("Boot HEV request/readback could not complete", error);
            return waitForVehicle(now);
        }
    }

    private Result waitForVehicle(long now) {
        if (now >= dueAt + 60000) return fail("vehicle readiness timed out", null);
        return Result.WAIT;
    }

    private void syncActual(Object manager, int actual) {
        if (actual == lastSynced || (actual != HEV && actual != EV)) return;
        try {
            BatterySocHook.dispatchActual(manager, VehicleControl.EV_HEV.function, actual);
            lastSynced = actual;
        } catch (Throwable error) {
            BatterySocHook.logFailure("Could not synchronize boot HEV readback to settings", error);
        }
    }

    private void complete() {
        commit(state.edit().putString(STATUS, DONE));
        status = DONE;
    }

    private Result fail(String reason, Throwable error) {
        status = FAILED;
        try {
            if (state != null && configured) commit(state.edit().putString(STATUS, FAILED));
        } catch (Throwable persistenceError) {
            BatterySocHook.logFailure("Could not record stopped HEV sequence", persistenceError);
        }
        Log.w(TAG, "Stopped boot restore: " + reason, error);
        return Result.STOP;
    }

    void cancel() {
        // This may be called by a host/Binder thread. Serialize it with gate.step().
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::cancel);
            return;
        }
        cancelled = true;
        status = CANCELLED;
        if (configured && state != null) {
            try {
                commit(state.edit().putString(STATUS, CANCELLED));
            } catch (Throwable error) {
                BatterySocHook.logFailure("Could not remember cancelled boot HEV sequence", error);
            }
        }
    }

    long nextDelayMs() { return Math.max(100, waitMs); }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Boot state was not saved");
    }

    void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (loader != null) loader.removeCallbacksAndMessages(null);
        if (loaderThread != null) loaderThread.quitSafely();
    }
}
