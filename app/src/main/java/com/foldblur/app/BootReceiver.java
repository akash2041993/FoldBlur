package com.foldblur.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Turns the effect back on after the phone restarts or the app updates. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.enabled(context) || !Settings.canDrawOverlays(context)) return;
        try {
            context.startForegroundService(new Intent(context, FoldEffectService.class));
        } catch (RuntimeException e) {
            Log.w("FoldBlur", "Could not start after boot", e);
        }
    }
}
