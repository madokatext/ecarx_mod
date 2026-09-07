package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** Shared contract without Xposed dependencies; also runs in the module process. */
final class BootSettings {
    static final Uri URI = Uri.parse("content://io.github.madokatext.ecarxmod.bootsettings");
    static final String READ = "read_boot_settings";
    static final String ENABLED = "force_hev_on_boot";
    static final String DELAY = "boot_delay_seconds";
    static final int MIN_DELAY = 5;
    static final int MAX_DELAY = 300;

    static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("boot_settings", Context.MODE_PRIVATE);
    }

    static int clampDelay(int seconds) {
        return Math.max(MIN_DELAY, Math.min(MAX_DELAY, seconds));
    }

    static Bundle readLocal(Context context) {
        SharedPreferences preferences = preferences(context);
        Bundle result = new Bundle();
        result.putBoolean(ENABLED, preferences.getBoolean(ENABLED, true));
        result.putInt(DELAY, clampDelay(preferences.getInt(DELAY, MIN_DELAY)));
        return result;
    }
}
