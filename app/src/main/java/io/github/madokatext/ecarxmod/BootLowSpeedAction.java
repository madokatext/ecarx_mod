package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import de.robv.android.xposed.XposedHelpers;

/** Independent low-speed boot action using the shared delay and its own request budget. */
final class BootLowSpeedAction {
    private static final String TAG = "EcarxSocFix";
    private static final String ENABLED = "low_speed_boot_enabled";
    private static final String STATUS = "low_speed_boot_status";
    private static final String WRITES = "low_speed_boot_writes";
    private static final String STARTED = "low_speed_boot_started";
    private static final String LAST_WRITE = "low_speed_boot_last_write";
    private static final String WAITING = "waiting";
    private static final long READY_TIMEOUT_MS = 60000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences state;
    private volatile boolean cancelled;
    private volatile boolean stopped;
    private long firstStepAt;
    private long waitMs = 1000;
    private int lastSynced = -1;

    void attach(Context context) {
        state = context.getSharedPreferences("ecarx_soc_fix", Context.MODE_PRIVATE);
    }

    void initializeForBoot(SharedPreferences.Editor editor, boolean enabled) {
        // Committed together with the HEV stage's boot ID and shared delay snapshot.
        editor.putBoolean(ENABLED, enabled)
                .putString(STATUS, cancelled ? "cancelled" : enabled ? WAITING : "disabled")
                .putInt(WRITES, 0).putLong(STARTED, 0).putLong(LAST_WRITE, 0);
    }

    boolean isPending() {
        return !stopped && !cancelled && state != null && state.getBoolean(ENABLED, false)
                && WAITING.equals(state.getString(STATUS, "disabled"));
    }

    // Returns true when finished/skipped. The caller supplies its own-write scope.
    boolean step(Object manager) {
        if (stopped || cancelled) return true;
        long now = SystemClock.elapsedRealtime();
        if (firstStepAt == 0) firstStepAt = now;
        waitMs = 1000;
        try {
            // Missing state on an in-place upgrade is skipped until the next OS boot.
            if (!isPending()) return true;
            long persistedStart = state.getLong(STARTED, 0);
            if (persistedStart == 0) {
                commit(state.edit().putLong(STARTED, firstStepAt));
            } else {
                firstStepAt = persistedStart;
            }
            if (!BatterySocHook.isCarReady(manager)) return waitForVehicle(now);
            int actual = (Integer) XposedHelpers.callMethod(manager, "getFunctionValue",
                    new Class<?>[]{int.class}, VehicleControl.LOW_SPEED_WARNING.function);
            syncActual(manager, actual);
            int writes = Math.max(0, state.getInt(WRITES, 0));
            long lastWrite = state.getLong(LAST_WRITE, 0);
            if (writes > 0 && now < lastWrite + 1000) {
                waitMs = lastWrite + 1000 - now;
                return false;
            }
            if (actual == 0) {
                return finish("done", "low-speed warning OFF readback confirmed");
            }
            if (writes >= 2) return finish("failed", "OFF not confirmed after two requests");
            if (actual != 1) return waitForVehicle(now);
            int ignition = (Integer) XposedHelpers.callMethod(manager, "getSensorEvent",
                    new Class<?>[]{int.class}, 2097408);
            if (ignition != 2097410 && ignition != 2097413
                    && ignition != 2097414 && ignition != 2097415) return waitForVehicle(now);
            if (now - firstStepAt >= READY_TIMEOUT_MS) {
                return finish("failed", "vehicle readiness timed out");
            }

            // Persist before calling: process restarts and reconnects cannot add retries.
            if (cancelled || stopped) return true;
            commit(state.edit().putInt(WRITES, writes + 1).putLong(LAST_WRITE, now));
            if (cancelled || stopped) return true;
            XposedHelpers.callMethod(manager, "setFunctionValue", new Class<?>[]{int.class, int.class},
                    VehicleControl.LOW_SPEED_WARNING.function, 0);
            Log.i(TAG, "Boot low-speed OFF request " + (writes + 1) + "/2");
            return false;
        } catch (Throwable error) {
            BatterySocHook.logFailure("Boot low-speed OFF request/readback could not complete", error);
            return waitForVehicle(now);
        }
    }

    private boolean waitForVehicle(long now) {
        if (now - firstStepAt >= READY_TIMEOUT_MS) {
            return finish("failed", "vehicle readiness timed out");
        }
        return false;
    }

    private void syncActual(Object manager, int actual) {
        if (actual == lastSynced || (actual != 0 && actual != 1)) return;
        try {
            BatterySocHook.dispatchActual(manager, VehicleControl.LOW_SPEED_WARNING.function, actual);
            lastSynced = actual;
        } catch (Throwable error) {
            // Notification failures never turn into additional vehicle requests.
            BatterySocHook.logFailure("Could not synchronize boot low-speed readback to settings", error);
        }
    }

    private boolean finish(String status, String reason) {
        try {
            if (state != null) commit(state.edit().putString(STATUS, status));
        } catch (Throwable error) {
            BatterySocHook.logFailure("Could not persist completed low-speed boot stage", error);
            cancelled = true;
        }
        Log.i(TAG, "Boot low-speed: " + reason);
        return true;
    }

    void cancel() {
        cancelled = true; // Invalidate a queued retry even when called by a Binder thread.
        if (stopped) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish("cancelled", "superseded by user control");
        } else {
            main.post(() -> { if (!stopped) finish("cancelled", "superseded by user control"); });
        }
    }

    long nextDelayMs() { return Math.max(100, waitMs); }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Low-speed boot state was not saved");
    }

    void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
    }
}
