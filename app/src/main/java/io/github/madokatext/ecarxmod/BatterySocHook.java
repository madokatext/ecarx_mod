package io.github.madokatext.ecarxmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Replays saved SOC for smart-charge widget requests and remembers charge mode for restart.
 * The host still decides whether a user click is permitted and which mode to request.
 */
public final class BatterySocHook implements IXposedHookLoadPackage {
    private static final String PACKAGE = "ecarx.settings";
    private static final String TAG = "EcarxSocFix";
    private static final String MANAGER =
            PACKAGE + ".vehicle.setting.entity.NegativeOneScreenWidgetManager";
    private static final String ATTR =
            PACKAGE + ".vehicle.setting.entity.NegativeOneScreenWidgetAttr";
    private static final String WIDGET =
            PACKAGE + ".vehicle.setting.widget.PowerBatteryChargeWidget";
    private static final String CAR_MANAGER = PACKAGE + ".function.CarFuncManager";
    private static final String PREVIOUS_CLICK = TAG + ".previousClick";
    private static final String PREFERENCES = "share_car_setting";
    private static final String TARGET_KEY = "TARGET_BATTERY_KEY";

    static final int CHARGE_MODE = 0x24150600;
    static final int MODE_ACTIVE = 0x24150601;
    static final int MODE_HOLD = 0x24150602;
    static final int MODE_OFF = 0x24150603;
    static final int TARGET_SOC = 0x24030100;
    private static final int DEFAULT_SOC = 30;
    private static final int MIN_SOC = 30;
    private static final int MAX_SOC = 85;

    // Widget handling and its mode write are synchronous in XCSettings2 3.0.0.0064.
    // Per-thread scopes prevent settings-page writes or other widgets from being affected.
    private final ThreadLocal<WidgetClick> currentClick = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) {
        if (!PACKAGE.equals(loaded.packageName)) {
            return;
        }

        XC_MethodHook.Unhook modeHook = null;
        XC_MethodHook.Unhook widgetHook = null;
        final BootStateRestorer restart = new BootStateRestorer();
        try {
            Class<?> managerClass = XposedHelpers.findClass(MANAGER, loaded.classLoader);
            Class<?> attrClass = XposedHelpers.findClass(ATTR, loaded.classLoader);
            Class<?> carClass = XposedHelpers.findClass(CAR_MANAGER, loaded.classLoader);
            Method widgetMethod = XposedHelpers.findMethodExact(
                    managerClass, "changeFunctionValue", Context.class, attrClass);

            modeHook = XposedHelpers.findAndHookMethod(
                    carClass, "setFunctionValue", int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) {
                                return;
                            }
                            int function = (Integer) param.args[0];
                            int mode = (Integer) param.args[1];
                            if (function != CHARGE_MODE || !isChargeMode(mode)) {
                                return;
                            }

                            // Do not use the wrapper's boolean result: this host always returns false.
                            try {
                                if (!isCarReady(param.thisObject)) {
                                    Log.w(TAG, "Skipped target: car service is disconnected");
                                    return;
                                }
                                // Remember requests from both widgets and the settings page.
                                // Startup/default value callbacks are deliberately not persisted.
                                restart.onModeRequested(param.thisObject, mode);
                                WidgetClick click = currentClick.get();
                                if (click == null || click.targetSent || mode == MODE_HOLD) {
                                    return;
                                }
                                int target = readTarget(click.context);
                                click.targetSent = true;
                                XposedHelpers.callMethod(param.thisObject, "setFunctionValue",
                                        new Class<?>[]{int.class, int.class}, TARGET_SOC, target);
                                Log.i(TAG, "Widget mode=0x" + Integer.toHexString(mode)
                                        + "; requested target SOC=" + target + "%");
                            } catch (Throwable error) {
                                logFailure("Could not replay saved target", error);
                            }
                        }
                    });

            widgetHook = XposedBridge.hookMethod(widgetMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra(PREVIOUS_CLICK, currentClick.get());
                    currentClick.remove();
                    try {
                        Object attr = param.args[1];
                        Context context = (Context) param.args[0];
                        if (context == null || attr == null) {
                            return;
                        }
                        ComponentName component = (ComponentName)
                                XposedHelpers.callMethod(attr, "getComponentName");
                        int function = (Integer) XposedHelpers.callMethod(attr, "getFunctionId");
                        int onValue = (Integer) XposedHelpers.callMethod(attr, "getFunctionOnValue");
                        if (component != null && PACKAGE.equals(component.getPackageName())
                                && WIDGET.equals(component.getClassName())
                                && function == CHARGE_MODE && onValue == MODE_ACTIVE) {
                            currentClick.set(new WidgetClick(context));
                        }
                    } catch (Throwable error) {
                        logFailure("Could not identify smart-charge widget", error);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    WidgetClick previous = (WidgetClick) param.getObjectExtra(PREVIOUS_CLICK);
                    if (previous == null) {
                        currentClick.remove();
                    } else {
                        currentClick.set(previous);
                    }
                }
            });

            if (modeHook == null || widgetHook == null) {
                throw new IllegalStateException("Framework did not install both hooks");
            }

            restart.install(loaded.classLoader, carClass);

            // Some LSPosed versions expose this extension. It prevents an optimized caller
            // from retaining an inlined copy of setFunctionValue; API 82 does not declare it.
            try {
                Method deoptimize = XposedBridge.class.getDeclaredMethod(
                        "deoptimizeMethod", java.lang.reflect.Member.class);
                deoptimize.invoke(null, widgetMethod);
            } catch (NoSuchMethodException ignored) {
                Log.i(TAG, "Using legacy hooks; optional deoptimization API is unavailable");
            } catch (Throwable error) {
                logFailure("Optional caller deoptimization failed", error);
            }

            XposedBridge.log(TAG + ": installed for " + loaded.processName);
        } catch (Throwable error) {
            restart.stop();
            if (widgetHook != null) {
                widgetHook.unhook();
            }
            if (modeHook != null) {
                modeHook.unhook();
            }
            logFailure("Unsupported host or hook installation failed", error);
        }
    }

    static boolean isChargeMode(int mode) {
        return mode == MODE_ACTIVE || mode == MODE_HOLD || mode == MODE_OFF;
    }

    static boolean isCarReady(Object manager) {
        return Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isCarConnected"))
                && XposedHelpers.getObjectField(manager, "mCarFunction") != null;
    }

    static int readTarget(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        int stored = preferences.getInt(TARGET_KEY, DEFAULT_SOC);
        int target = Math.max(MIN_SOC, Math.min(MAX_SOC, stored));
        if (target != stored) {
            Log.w(TAG, "Saved SOC " + stored + " is outside 30-85; sending " + target);
        }
        return target;
    }

    static void logFailure(String message, Throwable error) {
        Log.e(TAG, message, error);
        XposedBridge.log(TAG + ": " + message + " (" + error + ")");
    }

    private static final class WidgetClick {
        final Context context;
        boolean targetSent;

        WidgetClick(Context context) {
            this.context = context;
        }
    }
}
