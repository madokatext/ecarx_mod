package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Remembers requested modes, never the controller's transient boot/shutdown defaults. */
final class BootStateRestorer {
    private static final String TAG = "EcarxSocFix";
    private static final String STATE_FILE = "ecarx_soc_fix";
    private static final String LAST_MODE = "last_requested_charge_mode";
    private static final int UNKNOWN = -1;
    private static final int EPT_MODE = 0x22040d00;
    private static final int EPT_HEV = 0x22040d01;
    private static final int EPT_EV = 0x22040d02;
    private static final int EPT_SAVE = 0x22040d03;
    private static final int DRIVE_MODE = 0x22010100;
    private static final int COMFORT = 0x22010102;
    private static final int MAX_WRITES = 5;
    private static final int MAX_READINESS_POLLS = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> restoring = new ThreadLocal<>();
    private final AtomicInteger requestRevision = new AtomicInteger();
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final Runnable attempt = this::restoreIfReady;
    private Context context;
    private Object carManager;
    private boolean pending;
    private boolean stopped;
    private int writes;
    private int readinessPolls;

    void install(ClassLoader loader, Class<?> carClass) {
        hooks.add(XposedHelpers.findAndHookMethod("ecarx.settings.App", loader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        attach((Context) param.thisObject);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) {
                            // Also covers the host's synchronous, non-IConnectable path.
                            Object manager = XposedHelpers.callStaticMethod(carClass, "getInstance");
                            onConnectionChanged(manager, true);
                        }
                    }
                }));

        hooks.add(XposedHelpers.findAndHookMethod(carClass, "notifyObservers",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onConnectionChanged(param.thisObject, (Boolean) param.args[0]);
                    }
                }));

        // Observe the host's dispatcher without occupying/replacing any watcher registry slot.
        hooks.add(XposedHelpers.findAndHookMethod("ecarx.settings.function.CarFuncManager$2",
                loader, "onFunctionValueChanged", int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int function = (Integer) param.args[0];
                        if (function == EPT_MODE || function == DRIVE_MODE
                                || function == BatterySocHook.CHARGE_MODE) {
                            onVehicleSignal(function);
                        }
                    }
                }));

        for (XC_MethodHook.Unhook hook : hooks) {
            if (hook == null) {
                throw new IllegalStateException("Framework did not install restart hooks");
            }
        }
    }

    private synchronized void attach(Context host) {
        Context application = host.getApplicationContext();
        context = application != null ? application : host;
    }

    void onModeRequested(Object manager, int mode) {
        if (Boolean.TRUE.equals(restoring.get())) {
            return;
        }
        requestRevision.incrementAndGet();
        synchronized (this) {
            if (stopped || context == null) {
                return;
            }
            // User intent wins immediately over a queued boot restore or confirmation retry.
            pending = false;
            handler.removeCallbacks(attempt);
            carManager = manager;
            try {
                saveMode(mode);
            } catch (Throwable error) {
                // A persistence error must not prevent the existing widget SOC replay.
                BatterySocHook.logFailure("Could not remember requested mode", error);
            }
        }
    }

    private void onConnectionChanged(Object manager, boolean connected) {
        int revision = requestRevision.get();
        // Do not hold a lock or write vehicle properties inside a Binder callback.
        handler.post(() -> {
            synchronized (BootStateRestorer.this) {
                if (stopped || revision != requestRevision.get()) {
                    return;
                }
                carManager = manager;
                pending = true;
                writes = 0;
                readinessPolls = 0;
                handler.removeCallbacks(attempt);
                if (connected) {
                    schedule(500);
                }
            }
        });
    }

    private void onVehicleSignal(int function) {
        handler.post(() -> {
            synchronized (BootStateRestorer.this) {
                if (!pending || stopped) {
                    return;
                }
                if (function == EPT_MODE || function == DRIVE_MODE) {
                    readinessPolls = 0;
                }
                schedule(200);
            }
        });
    }

    private synchronized void restoreIfReady() {
        if (!pending || stopped || context == null || carManager == null) {
            return;
        }
        try {
            if (!BatterySocHook.isCarReady(carManager)) {
                waitForReady();
                return;
            }

            SharedPreferences state = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
            int actual = read(BatterySocHook.CHARGE_MODE);
            if (!BatterySocHook.isChargeMode(actual)) {
                waitForReady();
                return;
            }
            int desired = state.getInt(LAST_MODE, UNKNOWN);
            if (!state.contains(LAST_MODE)) {
                // First installation has no history: adopt a valid vehicle state, never guess ON.
                desired = actual;
                saveMode(desired);
            }
            if (!BatterySocHook.isChargeMode(desired)) {
                pending = false;
                Log.w(TAG, "Skipped restart restore: saved mode is invalid");
                return;
            }

            if (desired != BatterySocHook.MODE_OFF) {
                int ept = read(EPT_MODE);
                int drive = read(DRIVE_MODE);
                if (ept == EPT_EV || (drive != COMFORT && (drive & 0xffffff00) == DRIVE_MODE)) {
                    // Leave EV / other driving modes intact. Their next state event wakes us.
                    return;
                }
                if ((ept != EPT_HEV && ept != EPT_SAVE) || drive != COMFORT) {
                    waitForReady();
                    return;
                }
            }

            // Read/validate the target before requesting ACTIVE. HOLD maintains current SOC.
            int target = desired == BatterySocHook.MODE_HOLD ? 0 : BatterySocHook.readTarget(context);
            if (actual != desired && writes >= MAX_WRITES) {
                pending = false;
                Log.w(TAG, "Stopped restart restore: mode not confirmed after " + writes + " requests");
                return;
            }

            restoring.set(true);
            try {
                if (actual != desired) {
                    writes++;
                    write(BatterySocHook.CHARGE_MODE, desired);
                }
                if (desired != BatterySocHook.MODE_HOLD) {
                    // Replay even if the vehicle already has the desired switch state.
                    // When a mode change is confirmed on a later pass, replay again after it.
                    write(BatterySocHook.TARGET_SOC, target);
                    Log.i(TAG, "Restart restore mode=0x" + Integer.toHexString(desired)
                            + "; requested target SOC=" + target + "%");
                }
            } finally {
                restoring.remove();
            }

            if (actual == desired) {
                pending = false;
                Log.i(TAG, "Restart mode readback matched: 0x" + Integer.toHexString(desired));
            } else {
                schedule(1000);
            }
        } catch (Throwable error) {
            BatterySocHook.logFailure("Restart restore could not complete", error);
            if (++readinessPolls < MAX_READINESS_POLLS) {
                schedule(1000);
            }
        }
    }

    private void saveMode(int mode) {
        boolean saved = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
                .edit().putInt(LAST_MODE, mode).commit();
        if (!saved) {
            Log.e(TAG, "Could not persist requested charge mode");
        } else {
            Log.i(TAG, "Remembered charge mode=0x" + Integer.toHexString(mode));
        }
    }

    private int read(int function) {
        return (Integer) XposedHelpers.callMethod(carManager, "getFunctionValue",
                new Class<?>[]{int.class}, function);
    }

    private void write(int function, int value) {
        XposedHelpers.callMethod(carManager, "setFunctionValue",
                new Class<?>[]{int.class, int.class}, function, value);
    }

    private void waitForReady() {
        if (++readinessPolls < MAX_READINESS_POLLS) {
            schedule(1000);
        }
        // After the initial bounded polling, only connection/mode events wake restoration.
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(attempt);
        handler.postDelayed(attempt, delayMs);
    }

    synchronized void stop() {
        stopped = true;
        pending = false;
        handler.removeCallbacksAndMessages(null);
        for (XC_MethodHook.Unhook hook : hooks) {
            if (hook != null) {
                hook.unhook();
            }
        }
        hooks.clear();
    }
}
