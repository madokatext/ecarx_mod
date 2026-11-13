package io.github.madokatext.ecarxmod;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Own media client in the existing system-UID host; no hooks in individual music players. */
public final class MediaBridgeHook implements IXposedHookLoadPackage {
    private static MediaBridgeController controller;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) {
        if (!MediaBridgeState.HOST.equals(loaded.packageName)
                || !MediaBridgeState.HOST.equals(loaded.processName)) return;
        try {
            XposedHelpers.findAndHookMethod("ecarx.settings.App", loaded.classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || controller != null) return;
                            try {
                                controller = new MediaBridgeController((Context) param.thisObject);
                                controller.start();
                            } catch (Throwable error) {
                                MediaBridgeState.error("[sender] startup failed", error);
                            }
                        }
                    });
            XposedBridge.log(MediaBridgeState.TAG + ": startup hook installed for " + loaded.processName);
        } catch (Throwable error) {
            MediaBridgeState.error("[sender] could not install settings startup hook", error);
            XposedBridge.log(error);
        }
    }
}
