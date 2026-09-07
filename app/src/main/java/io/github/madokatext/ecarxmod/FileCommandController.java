package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Serializes file requests on the host main looper and confirms vehicle readback. */
final class FileCommandController {
    private static final String TAG = "EcarxSocFix";
    private static final int IGNITION = 2097408;
    private static final long READY_TIMEOUT_MS = 15000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> dispatching = new ThreadLocal<>();
    private final AtomicIntegerArray hostRevisions = new AtomicIntegerArray(VehicleControl.values().length);
    private final EnumMap<VehicleControl, Request> requests = new EnumMap<>(VehicleControl.class);
    private final EnumMap<VehicleControl, Integer> lastActual = new EnumMap<>(VehicleControl.class);
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final BootStateRestorer restart;
    private final ControlFileMonitor files = new ControlFileMonitor(this::onEdit);
    private Context context;
    private Object manager;
    private volatile boolean stopped;
    private boolean started;
    private long lastReadError;

    FileCommandController(BootStateRestorer restart) {
        this.restart = restart;
    }

    void install(ClassLoader loader, Class<?> carClass) {
        hooks.add(XposedHelpers.findAndHookMethod("ecarx.settings.App", loader,
                "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || stopped || started) return;
                        try {
                            context = (Context) param.thisObject;
                            manager = XposedHelpers.callStaticMethod(carClass, "getInstance");
                            started = true;
                            files.start();
                            handler.post(FileCommandController.this::pollStates);
                        } catch (Throwable error) {
                            BatterySocHook.logFailure("Could not start file controls", error);
                        }
                    }
                }));
        for (XC_MethodHook.Unhook hook : hooks) {
            if (hook == null) throw new IllegalStateException("File control hook was not installed");
        }
    }

    boolean isDispatching() {
        return Boolean.TRUE.equals(dispatching.get());
    }

    void onHostRequest(int function) {
        if (isDispatching() || restart.isRestoring()) return;
        for (VehicleControl control : VehicleControl.values()) {
            if (control.function == function || (control == VehicleControl.SMART_CHARGE
                    && function == BatterySocHook.TARGET_SOC)) {
                hostRevisions.incrementAndGet(control.ordinal());
                handler.post(() -> {
                    Request request = requests.get(control);
                    if (request != null && request.hostRevision != hostRevisions.get(control.ordinal())) {
                        finish(request, "superseded by a settings-app request");
                    }
                });
            }
        }
    }

    private void onEdit(VehicleControl control, ControlFileMonitor.Snapshot snapshot, Integer value) {
        // Capture before posting so a newer manual UI action invalidates this edit too.
        int revision = hostRevisions.get(control.ordinal());
        handler.post(() -> {
            if (stopped) return;
            Request previous = requests.get(control);
            if (previous != null) finish(previous, "control file changed");
            if (value == null || revision != hostRevisions.get(control.ordinal())
                    || !files.isCurrent(control, snapshot)) return;
            if (control == VehicleControl.SMART_CHARGE || control == VehicleControl.EV_HEV) {
                restart.takeFileModeControl();
            } else if (control == VehicleControl.LOW_SPEED_WARNING) {
                restart.takeFileLowSpeedControl();
            }
            Request request = new Request(control, snapshot,
                    value == 1 ? control.on : control.off, revision);
            requests.put(control, request);
            send(request);
        });
    }

    private boolean current(Request request) {
        if (stopped || requests.get(request.control) != request) return false;
        if (request.hostRevision != hostRevisions.get(request.control.ordinal())
                || !files.isCurrent(request.control, request.snapshot)) {
            finish(request, "superseded");
            return false;
        }
        return true;
    }

    private void send(Request request) {
        if (!current(request)) return;
        boolean attemptedWrite = false;
        try {
            if (!BatterySocHook.isCarReady(manager) || !allowed(request)) {
                waitForReady(request);
                return;
            }
            if (request.attempts >= 2) {
                finish(request, "retry limit reached");
                return;
            }
            // Count before calling: an exception may occur after the service accepted a write.
            request.attempts++;
            attemptedWrite = true;
            request.target = request.control == VehicleControl.SMART_CHARGE
                    ? BatterySocHook.readTarget(context) : -1;
            dispatching.set(true);
            try {
                try {
                    write(request.control.function, request.desired);
                } finally {
                    // Both ON and OFF carry the current stock-app target. Never rely on false
                    // from the stock wrapper, which discards the underlying setter's result.
                    if (request.target >= 0) write(BatterySocHook.TARGET_SOC, request.target);
                }
                Log.i(TAG, "File " + request.control.name + ": request " + request.attempts
                        + "/2, value=0x" + Integer.toHexString(request.desired));
            } finally {
                dispatching.remove();
            }
        } catch (Throwable error) {
            BatterySocHook.logFailure("File request could not complete", error);
            if (!attemptedWrite) {
                waitForReady(request);
                return;
            }
        }
        schedule(request, () -> confirm(request), 1000);
    }

    private boolean allowed(Request request) {
        if (request.control == VehicleControl.SMART_CHARGE) {
            if (request.desired == BatterySocHook.MODE_OFF) return true;
            int ept = read(VehicleControl.EV_HEV.function);
            return (ept == 0x22040d01 || ept == 0x22040d03)
                    && read(0x22010100) == 0x22010102;
        }
        int ignition = (Integer) XposedHelpers.callMethod(manager, "getSensorEvent",
                new Class<?>[]{int.class}, IGNITION);
        if (request.control == VehicleControl.EV_HEV) {
            return ignition == 2097413 || ignition == 2097414 || ignition == 2097415;
        }
        return ignition == 2097410 || ignition == 2097413
                || ignition == 2097414 || ignition == 2097415;
    }

    private void waitForReady(Request request) {
        if (SystemClock.elapsedRealtime() >= request.deadline) {
            finish(request, "vehicle service or operating conditions unavailable");
        } else {
            schedule(request, () -> send(request), 500);
        }
    }

    private void confirm(Request request) {
        if (!current(request)) return;
        boolean matched = false;
        try {
            if (BatterySocHook.isCarReady(manager)) {
                int actual = read(request.control.function);
                syncApp(request.control, actual, true);
                matched = actual == request.desired;
                if (request.control == VehicleControl.SMART_CHARGE) {
                    // Only confirmed vehicle state becomes restart history for file requests.
                    if (BatterySocHook.isChargeMode(actual)) restart.rememberFileMode(actual);
                    int target = read(BatterySocHook.TARGET_SOC);
                    matched &= request.target >= 30 && target == request.target;
                    if (target < 30 || target > 85) {
                        Log.w(TAG, "File smart_charge: target SOC readback unavailable");
                    }
                }
            }
        } catch (Throwable error) {
            BatterySocHook.logFailure("File request readback unavailable", error);
        }
        if (matched) {
            finish(request, "vehicle readback matched");
        } else if (request.attempts < 2) {
            send(request);
        } else {
            finish(request, "not confirmed after two requests; waiting for a new file edit");
        }
    }

    private void pollStates() {
        if (stopped) return;
        EnumMap<VehicleControl, String> states = new EnumMap<>(VehicleControl.class);
        for (VehicleControl control : VehicleControl.values()) states.put(control, "");
        try {
            if (BatterySocHook.isCarReady(manager)) {
                for (VehicleControl control : VehicleControl.values()) {
                    try {
                        int actual = read(control.function);
                        states.put(control, control.stateValue(actual));
                        syncApp(control, actual, false);
                    } catch (Throwable error) {
                        lastActual.remove(control);
                        reportReadError(error);
                    }
                }
            } else {
                lastActual.clear();
            }
        } catch (Throwable error) {
            lastActual.clear();
            reportReadError(error);
        }
        files.publishStates(states);
        handler.postDelayed(this::pollStates, 1000);
    }

    private void syncApp(VehicleControl control, int actual, boolean force) {
        if (control.stateValue(actual).isEmpty()) {
            lastActual.remove(control);
            return;
        }
        Integer previous = lastActual.get(control);
        if (!force && previous != null && previous == actual) return;
        try {
            // Stock settings keep these switch states in callback-updated members, not extra
            // preferences. Use their real dispatcher for settings, Kanzi and widget observers.
            BatterySocHook.dispatchActual(manager, control.function, actual);
            lastActual.put(control, actual);
        } catch (Throwable error) {
            reportReadError(error); // A UI notification failure must not cause vehicle rewrites.
        }
    }

    private void reportReadError(Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (lastReadError == 0 || now - lastReadError >= 30000) {
            lastReadError = now;
            BatterySocHook.logFailure("Could not synchronize actual vehicle state", error);
        }
    }

    private int read(int function) {
        return (Integer) XposedHelpers.callMethod(manager, "getFunctionValue",
                new Class<?>[]{int.class}, function);
    }

    private void write(int function, int value) {
        XposedHelpers.callMethod(manager, "setFunctionValue",
                new Class<?>[]{int.class, int.class}, function, value);
    }

    private void schedule(Request request, Runnable runnable, long delay) {
        if (request.scheduled != null) handler.removeCallbacks(request.scheduled);
        request.scheduled = runnable;
        handler.postDelayed(runnable, delay);
    }

    private void finish(Request request, String reason) {
        if (request.scheduled != null) handler.removeCallbacks(request.scheduled);
        if (requests.get(request.control) == request) requests.remove(request.control);
        Log.i(TAG, "File " + request.control.name + ": " + reason);
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        files.stop();
        for (XC_MethodHook.Unhook hook : hooks) if (hook != null) hook.unhook();
        hooks.clear();
    }

    private static final class Request {
        final VehicleControl control;
        final ControlFileMonitor.Snapshot snapshot;
        final int desired;
        final int hostRevision;
        final long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
        int attempts;
        int target = -1;
        Runnable scheduled;

        Request(VehicleControl control, ControlFileMonitor.Snapshot snapshot, int desired, int revision) {
            this.control = control;
            this.snapshot = snapshot;
            this.desired = desired;
            this.hostRevision = revision;
        }
    }
}
