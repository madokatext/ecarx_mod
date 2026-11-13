package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.os.SystemClock;

import de.robv.android.xposed.XposedHelpers;

/** Read-only display data from the settings host, independent of boot/file command requests. */
final class MediaVehicleState {
    private static final int UNKNOWN = -1;
    private final Context context;
    private Object manager;
    private String observed;
    private long nextErrorLog;

    MediaVehicleState(Context context) {
        this.context = context;
    }

    String poll() {
        int power = UNKNOWN;
        int charge = UNKNOWN;
        int target = UNKNOWN;
        boolean ready = false;
        try {
            // This is the target shown/saved by the stock battery page, not remaining battery SOC.
            target = BatterySocHook.readTarget(context);
        } catch (Throwable error) { failure("saved target SOC", error); }
        try {
            if (manager == null) {
                Class<?> type = XposedHelpers.findClass("ecarx.settings.function.CarFuncManager",
                        context.getClassLoader());
                manager = XposedHelpers.callStaticMethod(type, "getInstance");
            }
            ready = manager != null && BatterySocHook.isCarReady(manager);
            if (ready) {
                power = read(VehicleControl.EV_HEV.function);
                charge = read(BatterySocHook.CHARGE_MODE);
            }
        } catch (Throwable error) { failure("car connection", error); }

        String mode = power == VehicleControl.EV_HEV.off ? "EV"
                : power == VehicleControl.EV_HEV.on ? "HEV" : "未知";
        String switchValue = VehicleControl.SMART_CHARGE.stateValue(charge);
        // Match the stock smart-charge switch: SOC_HOLD is a separate mode and displays as off.
        String enabled = switchValue.equals("1") ? "开" : switchValue.equals("0") ? "关" : "未知";
        String display = mode + " - 保电 " + enabled + " - "
                + (target < 0 ? "未知" : Integer.toString(target)) + "%";
        String detail = "ready=" + ready + " power=" + hex(power) + " charge=" + hex(charge)
                + " savedTarget=" + target + " display=\"" + display + "\"";
        if (!detail.equals(observed)) {
            observed = detail;
            MediaBridgeState.info("[vehicle] " + detail);
        }
        return display;
    }

    private int read(int function) {
        try {
            return (Integer) XposedHelpers.callMethod(manager, "getFunctionValue",
                    new Class<?>[]{int.class}, function);
        } catch (Throwable error) {
            failure("getFunctionValue " + hex(function), error);
            return UNKNOWN;
        }
    }

    private void failure(String stage, Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextErrorLog) return;
        nextErrorLog = now + 30000;
        MediaBridgeState.error("[vehicle] cannot read " + stage + "; showing unknown for unavailable fields", error);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value);
    }
}
