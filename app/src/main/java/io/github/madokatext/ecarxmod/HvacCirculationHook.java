package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** XCHvac 2.3.0.026: retain manual circulation while AQS/G-clean is operating. */
public final class HvacCirculationHook implements IXposedHookLoadPackage {
    private static final String PACKAGE = "ecarx.hvac.app";
    private static final String CAR_MANAGER = PACKAGE + ".utils.CarFuncManager";
    private static final String TAG = "EcarxHvacLock";
    private static final VehicleControl CONTROL = VehicleControl.HVAC_CIRCULATION;
    private static final int UNKNOWN = 253;
    private static final int AUTO_CIRCULATION = 0x10030103;
    private static final int POWER = 0x10010100;
    private static final int AQS = 0x10080200;
    private static final int G_CLEAN = 0x10100400;
    private static final int FRONT_DEFROST = 0x10040100;
    private static final int MAX_DEFROST = 0x10040200;
    private static final int AC_MAX = 0x10010400;
    private static final String PREFERENCES = "ecarx_circulation_lock";
    private static final String CHOICE = "manual_circulation";
    private static final String CORRECTIONS = "correction_attempts";
    private static final String EPISODE = "correction_episode_pending";
    private static final String AWAITING_MANUAL = "awaiting_manual_readback";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollState;
    private final ThreadLocal<Boolean> dispatching = new ThreadLocal<>();
    private final AtomicInteger hostRevision = new AtomicInteger();
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final ControlFileMonitor files = new ControlFileMonitor(this::onEdit, CONTROL);
    // All mutable controller state below is owned by the main looper.
    private Object manager;
    private SharedPreferences preferences;
    private volatile boolean started;
    private volatile boolean stopped;
    private int desired = UNKNOWN;
    private int corrections;
    private boolean correctionEpisode;
    private boolean awaitingManual;
    private int lastActual = UNKNOWN;
    private boolean automationActive;
    private long automationExitUntil;
    private boolean protecting;
    private long protectionUntil;
    private long settleUntil;
    private long nextCorrectionAt;
    private long lastError;
    private Request request;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) {
        if (!PACKAGE.equals(loaded.packageName) || !PACKAGE.equals(loaded.processName)) return;
        try {
            Class<?> carClass = XposedHelpers.findClass(CAR_MANAGER, loaded.classLoader);
            XposedHelpers.findField(carClass, "mCarFunction");
            XposedHelpers.findField(carClass, "mWatcher");
            XC_MethodHook setter = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!started || stopped || Boolean.TRUE.equals(dispatching.get())) return;
                    int function = (Integer) param.args[0];
                    // Circulation and the two automation switches are global (zone 0).
                    if (param.args.length == 3 && (Integer) param.args[1] != 0) return;
                    int value = (Integer) param.args[param.args.length - 1];
                    if (function == CONTROL.function && isManual(value)) {
                        int revision = hostRevision.incrementAndGet();
                        onMain(() -> {
                            if (revision != hostRevision.get()) return;
                            finishFile("superseded by manual HVAC control", false);
                            remember(value, 0, false, true);
                            settleUntil = SystemClock.elapsedRealtime() + 1500;
                            nextCorrectionAt = settleUntil;
                        });
                    } else if (isProtectiveFunction(function)) {
                        // One-touch defog/defrost and MAX AC are intentional compound actions.
                        // Yield before their circulation changes, including a delayed flag.
                        hostRevision.incrementAndGet();
                        onMain(() -> {
                            finishFile("superseded by defog/defrost or MAX AC", false);
                            protecting = true;
                            protectionUntil = SystemClock.elapsedRealtime() + 3000;
                            wake();
                        });
                    } else if (function == AQS || function == G_CLEAN) {
                        // Capture BEFORE enabling: the ECU may change circulation before
                        // reporting the AQS/G-clean switch callback. Do not reset the budget.
                        try {
                            int before = read(CONTROL.function);
                            onMain(() -> {
                                if (!automationActive && !awaitingManual && request == null && isManual(before)
                                        && SystemClock.elapsedRealtime() >= settleUntil) {
                                    remember(before, corrections, correctionEpisode, false);
                                }
                            });
                        } catch (Throwable error) {
                            report("Could not capture circulation before automation request", error);
                        }
                    }
                }
            };
            hooks.add(XposedHelpers.findAndHookMethod(carClass, "setFunctionValue",
                    int.class, int.class, setter));
            hooks.add(XposedHelpers.findAndHookMethod(carClass, "setFunctionValue",
                    int.class, int.class, int.class, setter));
            hooks.add(XposedHelpers.findAndHookMethod(CAR_MANAGER + "$4", loaded.classLoader,
                    "onFunctionValueChanged", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int function = (Integer) param.args[0];
                            if ((Integer) param.args[1] != 0) return;
                            if (isProtectiveFunction(function) && (Integer) param.args[2] == 1) {
                                onMain(() -> {
                                    if (!protecting && SystemClock.elapsedRealtime() >= protectionUntil) {
                                        finishFile("superseded by a protective HVAC mode", false);
                                    }
                                    protecting = true;
                                    protectionUntil = SystemClock.elapsedRealtime() + 3000;
                                });
                            }
                            if (function == CONTROL.function
                                    || function == AQS || function == G_CLEAN || function == POWER
                                    || isProtectiveFunction(function)) wake();
                        }
                    }));
            hooks.add(XposedHelpers.findAndHookMethod(carClass, "notifyObservers",
                    boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            wake(); // A reconnect never resets an unresolved episode's attempts.
                        }
                    }));
            hooks.add(XposedHelpers.findAndHookMethod(PACKAGE + ".MyApplication", loaded.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || stopped || started) return;
                            try {
                                Context context = (Context) param.thisObject;
                                preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
                                loadChoice();
                                manager = XposedHelpers.callStaticMethod(carClass, "getInstance");
                                started = true;
                                files.start();
                                handler.post(poll);
                            } catch (Throwable error) {
                                report("Could not start HVAC circulation control", error);
                                stop();
                            }
                        }
                    }));
            for (XC_MethodHook.Unhook hook : hooks) {
                if (hook == null) throw new IllegalStateException("HVAC hook was not installed");
            }
            deoptimizeCallers(loaded.classLoader);
            XposedBridge.log(TAG + ": installed for " + loaded.processName);
        } catch (Throwable error) {
            stop();
            report("Unsupported HVAC host or hook installation failed", error);
        }
    }

    private void loadChoice() {
        try {
            desired = preferences.getInt(CHOICE, UNKNOWN);
            corrections = Math.max(0, Math.min(2, preferences.getInt(CORRECTIONS,
                    preferences.contains(CHOICE) ? 2 : 0)));
            correctionEpisode = preferences.getBoolean(EPISODE, corrections >= 2);
            awaitingManual = preferences.getBoolean(AWAITING_MANUAL, false);
            if (!isManual(desired)) desired = UNKNOWN;
        } catch (RuntimeException error) {
            desired = UNKNOWN;
            corrections = 2;
            correctionEpisode = true;
            awaitingManual = true;
            report("Invalid saved lock state; manual selection required to rearm", error);
        }
    }

    private boolean remember(int mode, int attempts, boolean episode, boolean waiting) {
        desired = mode;
        corrections = attempts;
        correctionEpisode = episode;
        awaitingManual = waiting;
        try {
            // Persist BEFORE sending. Restarting the process cannot add correction attempts.
            if (preferences.edit().putInt(CHOICE, mode).putInt(CORRECTIONS, attempts)
                    .putBoolean(EPISODE, episode).putBoolean(AWAITING_MANUAL, waiting).commit()) {
                return true;
            }
            throw new IllegalStateException("Could not commit circulation lock state");
        } catch (RuntimeException error) {
            corrections = 2; // No automatic write without a durable attempt budget.
            correctionEpisode = true;
            awaitingManual = true;
            report("Automatic correction paused because persistence failed", error);
            return false;
        }
    }

    private void onEdit(VehicleControl control, ControlFileMonitor.Snapshot snapshot, Integer value) {
        int revision = hostRevision.get();
        handler.post(() -> {
            if (stopped || !started) return;
            finishFile("control file changed", true);
            if (value == null || revision != hostRevision.get() || !files.isCurrent(CONTROL, snapshot)) return;
            int mode = value == 1 ? CONTROL.on : CONTROL.off;
            remember(mode, 0, false, true);
            request = new Request(snapshot, mode, revision);
            sendFile(request);
        });
    }

    private boolean current(Request item) {
        if (stopped || request != item) return false;
        if (item.revision != hostRevision.get() || !files.isCurrent(CONTROL, item.snapshot)) {
            finishFile("superseded", true);
            return false;
        }
        return true;
    }

    private void sendFile(Request item) {
        if (!current(item)) return;
        if (SystemClock.elapsedRealtime() >= item.deadline) {
            finishFile("vehicle service or circulation control unavailable for 15 seconds", true);
            return;
        }
        boolean attempted = false;
        try {
            if (!canWrite()) {
                schedule(item, () -> sendFile(item), 500);
                return;
            }
            if (item.attempts >= 2) {
                finishFile("not confirmed after two requests", true);
                return;
            }
            item.attempts++;
            attempted = true;
            write(item.mode);
            Log.i(TAG, "File hvac_circulation: request " + item.attempts + "/2");
        } catch (Throwable error) {
            report("Could not send file circulation request", error);
        }
        if (attempted) schedule(item, () -> confirmFile(item), 1000);
        else schedule(item, () -> sendFile(item), 500);
    }

    private void confirmFile(Request item) {
        if (!current(item)) return;
        try {
            if (ready()) {
                int actual = read(CONTROL.function);
                publish(actual);
                if (actual == item.mode) {
                    // File attempts and automatic corrections are separate bounded budgets.
                    remember(item.mode, 0, false, false);
                    finishFile("vehicle readback matched", false);
                    wake();
                    return;
                }
            }
        } catch (Throwable error) {
            report("File circulation readback unavailable", error);
        }
        if (item.attempts < 2) sendFile(item);
        else finishFile("not confirmed after two requests; waiting for manual input", true);
    }

    private void schedule(Request item, Runnable action, long delay) {
        if (item.scheduled != null) handler.removeCallbacks(item.scheduled);
        item.scheduled = action;
        handler.postDelayed(action, delay);
    }

    private void finishFile(String reason, boolean exhaust) {
        if (request == null) return;
        if (request.scheduled != null) handler.removeCallbacks(request.scheduled);
        request = null;
        // Never turn an unsuccessful/cancelled file request into extra guard retries.
        if (exhaust) remember(desired, 2, true, true);
        Log.i(TAG, "File hvac_circulation: " + reason);
    }

    private void pollState() {
        if (stopped || !started) return;
        handler.removeCallbacks(poll);
        int actual = UNKNOWN;
        try {
            if (!ready()) {
                publish(UNKNOWN);
                return;
            }
            actual = read(CONTROL.function);
            publish(actual);
            long now = SystemClock.elapsedRealtime();
            boolean protectedMode = protectionOn(FRONT_DEFROST)
                    || protectionOn(MAX_DEFROST) || protectionOn(AC_MAX);
            if (protecting && !protectedMode) protectionUntil = now + 3000;
            if (protectedMode && !protecting && now >= protectionUntil) {
                finishFile("superseded by a protective HVAC mode", false);
            }
            protecting = protectedMode;
            if (protectedMode || now < protectionUntil) {
                // Accept necessary circulation transitions as the new baseline. Do not
                // restore the old manual mode when defog/defrost/MAX AC subsequently exits.
                if (request == null && now >= settleUntil && isManual(actual)
                        && (actual != desired || correctionEpisode || awaitingManual)) {
                    remember(actual, 0, false, false);
                }
                automationActive = false;
                automationExitUntil = 0;
                return;
            }
            boolean active = switchOn(AQS) || switchOn(G_CLEAN);
            if (automationActive && !active) automationExitUntil = now + 3000;
            automationActive = active;
            boolean guarding = active || now < automationExitUntil;
            if (!isManual(desired) && isManual(actual)) {
                remember(actual, corrections, correctionEpisode, awaitingManual);
            }
            // A genuine matching readback ends an episode. Repeated mismatch callbacks,
            // polling and reconnects cannot rearm its two attempts. Manual/file failures
            // must first match too, so their failure is never misclassified as auto drift.
            if (actual == desired && isManual(actual) && request == null
                    && (correctionEpisode || awaitingManual)) {
                remember(desired, corrections, false, false);
            }
            // Other HVAC behavior is outside the AQS/G-clean lock. Keep its real baseline,
            // without giving it another correction budget or cancelling a pending file edit.
            if (!guarding && !awaitingManual && request == null && now >= settleUntil
                    && isManual(actual) && actual != desired) {
                remember(actual, corrections, false, false);
            }
            if (guarding && !awaitingManual && request == null && isManual(desired) && actual != desired
                    && (isManual(actual) || actual == AUTO_CIRCULATION)
                    && (!correctionEpisode || corrections < 2)
                    && now >= settleUntil && now >= nextCorrectionAt && canWrite()) {
                nextCorrectionAt = now + 1000;
                if (remember(desired, correctionEpisode ? corrections + 1 : 1, true, false)) {
                    Log.i(TAG, "AQS/G-clean circulation correction " + corrections + "/2");
                    write(desired);
                }
            }
        } catch (Throwable error) {
            publish(actual); // Empty when reading circulation failed; never retain stale state.
            report("HVAC circulation synchronization unavailable", error);
        } finally {
            if (!stopped) {
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, 1000);
            }
        }
    }

    private void publish(int actual) {
        EnumMap<VehicleControl, String> state = new EnumMap<>(VehicleControl.class);
        state.put(CONTROL, CONTROL.stateValue(actual));
        files.publishStates(state);
        if (!isManual(actual)) {
            lastActual = UNKNOWN;
            return;
        }
        if (lastActual == actual) return;
        // Use the stock watcher only with genuine vehicle readback; never conceal a drift.
        lastActual = actual;
        try {
            Object watcher = XposedHelpers.getObjectField(manager, "mWatcher");
            XposedHelpers.callMethod(watcher, "onFunctionValueChanged",
                    new Class<?>[]{int.class, int.class, int.class}, CONTROL.function, 0, actual);
        } catch (Throwable error) {
            lastActual = UNKNOWN;
            report("Could not update the HVAC circulation display", error);
        }
    }

    private boolean ready() {
        return manager != null && BatterySocHook.isCarReady(manager);
    }

    private boolean canWrite() {
        if (!ready() || read(POWER) != 1) return false;
        Object status = XposedHelpers.callMethod(manager, "isFunctionSupported",
                new Class<?>[]{int.class}, CONTROL.function);
        return status instanceof Enum && "active".equals(((Enum<?>) status).name());
    }

    private boolean switchOn(int function) {
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isFunctionSupport",
                new Class<?>[]{int.class}, function))) return false;
        int value = read(function);
        if (value != 0 && value != 1) throw new IllegalStateException(
                "Unknown HVAC switch 0x" + Integer.toHexString(function) + ": " + value);
        return value == 1;
    }

    private boolean protectionOn(int function) {
        // MAX front defrost is an optional SDK property omitted from this host's cached
        // function list. Query capability directly so it cannot silently bypass the guard.
        Object status = XposedHelpers.callMethod(manager, "isFunctionSupported",
                new Class<?>[]{int.class}, function);
        if (!(status instanceof Enum)) return false;
        String name = ((Enum<?>) status).name();
        if (!"active".equals(name) && !"notactive".equals(name)) return false;
        int value = read(function);
        if (value != 0 && value != 1) throw new IllegalStateException(
                "Unknown protective HVAC mode 0x" + Integer.toHexString(function) + ": " + value);
        return value == 1;
    }

    private static boolean isProtectiveFunction(int function) {
        return function == FRONT_DEFROST || function == MAX_DEFROST || function == AC_MAX;
    }

    private int read(int function) {
        return (Integer) XposedHelpers.callMethod(manager, "getFunctionValue",
                new Class<?>[]{int.class}, function);
    }

    private void write(int mode) {
        dispatching.set(true);
        try {
            // The stock wrapper discards the underlying result and always returns false.
            XposedHelpers.callMethod(manager, "setFunctionValue",
                    new Class<?>[]{int.class, int.class}, CONTROL.function, mode);
        } finally {
            dispatching.remove();
        }
    }

    private static boolean isManual(int mode) {
        return mode == CONTROL.off || mode == CONTROL.on;
    }

    private void onMain(Runnable action) {
        Runnable guarded = () -> { if (started && !stopped) action.run(); };
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else handler.post(guarded);
    }

    private void wake() {
        onMain(() -> {
            handler.removeCallbacks(poll);
            handler.post(poll);
        });
    }

    private void deoptimizeCallers(ClassLoader loader) {
        try {
            Method deoptimize = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
            Class<?> presenter = XposedHelpers.findClass(PACKAGE + ".presenter.AirConditionPresenter", loader);
            for (Method method : presenter.getDeclaredMethods()) {
                if (method.getName().equals("setCycleStyle") || method.getName().equals("setAcClean")
                        || method.getName().equals("setAqsStatue") || method.getName().equals("defrost")
                        || method.getName().equals("setAcMax")) deoptimize.invoke(null, method);
            }
        } catch (NoSuchMethodException ignored) {
            Log.i(TAG, "Optional legacy deoptimization API is unavailable");
        } catch (Throwable error) {
            report("Optional HVAC caller deoptimization unavailable", error);
        }
    }

    private void report(String message, Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (lastError == 0 || now - lastError >= 30000) {
            lastError = now;
            Log.e(TAG, message, error);
            XposedBridge.log(TAG + ": " + message + " (" + error + ")");
        }
    }

    private void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        files.stop();
        for (XC_MethodHook.Unhook hook : hooks) if (hook != null) hook.unhook();
        hooks.clear();
    }

    private static final class Request {
        final ControlFileMonitor.Snapshot snapshot;
        final int mode;
        final int revision;
        final long deadline = SystemClock.elapsedRealtime() + 15000;
        int attempts;
        Runnable scheduled;

        Request(ControlFileMonitor.Snapshot snapshot, int mode, int revision) {
            this.snapshot = snapshot;
            this.mode = mode;
            this.revision = revision;
        }
    }
}
