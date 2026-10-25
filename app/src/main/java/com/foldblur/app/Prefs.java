package com.foldblur.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings. */
final class Prefs {
    static final String FILE = "foldblur";

    static final String ENABLED = "enabled";
    static final String BLUR = "blur";            // max blur radius in pixels
    static final String FROST = "frost";          // 0..100, how milky the glass looks
    static final String DARK = "dark";            // dark glass instead of light glass
    static final String ON_CLOSE = "on_close";    // also play when folding shut
    static final String CLOSED_ANGLE = "closed_angle";
    static final String OPEN_ANGLE = "open_angle";

    static final int DEFAULT_BLUR = 120;
    static final int DEFAULT_FROST = 18;
    static final int DEFAULT_CLOSED_ANGLE = 10;
    static final int DEFAULT_OPEN_ANGLE = 165;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return get(c).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        get(c).edit().putBoolean(ENABLED, on).apply();
    }
}
