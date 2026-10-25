package com.foldblur.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.function.Consumer;

/**
 * Runs in the background. Reads the hinge angle sensor and draws a see-through blur window
 * over the screen while the phone is being opened or closed.
 */
public class FoldEffectService extends Service implements
        SensorEventListener,
        Choreographer.FrameCallback,
        DisplayManager.DisplayListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_TEST = "com.foldblur.app.TEST";
    static final String ACTION_STOP = "com.foldblur.app.STOP";

    static volatile boolean running;

    private static final String TAG = "FoldBlur";
    private static final String CHANNEL = "effect";
    private static final int NOTIF_ID = 1;

    // Android lets touches pass through an overlay only up to this opacity.
    private static final float MAX_WINDOW_ALPHA = 0.8f;

    private final EffectEngine engine = new EffectEngine();
    private final SparseArray<Overlay> overlays = new SparseArray<>();

    private Handler handler;
    private SensorManager sensors;
    private Sensor hinge;
    private DisplayManager displays;
    private WindowManager mainWm;

    private boolean stopping;
    private boolean frameScheduled;
    private boolean blurSupported;
    private int maxBlur;
    private float frost;
    private boolean dark;
    private long lastDefaultSize = -1;

    private final Consumer<Boolean> blurListener = new Consumer<Boolean>() {
        @Override
        public void accept(Boolean enabled) {
            blurSupported = enabled != null && enabled;
        }
    };

    private static final class Overlay {
        WindowManager wm;
        View view;
        WindowManager.LayoutParams lp;
        boolean drawnOnce;
        boolean visible;
        float alpha;
        int radius;
        int color;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());

        if (!startAsForeground() || !Settings.canDrawOverlays(this)) {
            stopping = true;
            stopSelf();
            return;
        }

        mainWm = getSystemService(WindowManager.class);
        blurSupported = mainWm.isCrossWindowBlurEnabled();
        mainWm.addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);

        loadPrefs();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this);

        displays = getSystemService(DisplayManager.class);
        for (Display d : displays.getDisplays()) {
            addOverlay(d);
        }
        displays.registerDisplayListener(this, handler);
        lastDefaultSize = defaultDisplaySize();

        sensors = getSystemService(SensorManager.class);
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge != null) {
            sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST, handler);
        } else {
            Log.w(TAG, "No hinge angle sensor, using screen-switch backup mode");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopping) return START_NOT_STICKY;
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                Prefs.setEnabled(this, false);
                stopping = true;
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_TEST.equals(action)) {
                engine.triggerPulse(SystemClock.uptimeMillis());
                scheduleFrame();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (sensors != null) sensors.unregisterListener(this);
        if (displays != null) displays.unregisterDisplayListener(this);
        if (mainWm != null) mainWm.removeCrossWindowBlurEnabledListener(blurListener);
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            frameScheduled = false;
        }
        for (int i = overlays.size() - 1; i >= 0; i--) {
            removeOverlay(overlays.keyAt(i));
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- Notification (Android requires one for background services) ----------

    private boolean startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FoldEffectService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat),
                        getString(R.string.stop), stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start in foreground", e);
            return false;
        }
    }

    // ---------- Settings ----------

    private void loadPrefs() {
        SharedPreferences p = Prefs.get(this);
        maxBlur = Math.min(150, p.getInt(Prefs.BLUR, Prefs.DEFAULT_BLUR));
        frost = p.getInt(Prefs.FROST, Prefs.DEFAULT_FROST) / 100f;
        dark = p.getBoolean(Prefs.DARK, false);
        engine.effectWhenClosing = p.getBoolean(Prefs.ON_CLOSE, true);
        engine.closedAngle = p.getInt(Prefs.CLOSED_ANGLE, Prefs.DEFAULT_CLOSED_ANGLE);
        engine.openAngle = p.getInt(Prefs.OPEN_ANGLE, Prefs.DEFAULT_OPEN_ANGLE);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        loadPrefs();
        if (Prefs.ENABLED.equals(key) && !sp.getBoolean(Prefs.ENABLED, false) && !stopping) {
            stopping = true;
            stopSelf();
        }
    }

    // ---------- Overlay windows (one per screen) ----------

    private void addOverlay(Display d) {
        int id = d.getDisplayId();
        if (overlays.get(id) != null) return;
        if (id != Display.DEFAULT_DISPLAY && (d.getFlags() & Display.FLAG_PRIVATE) != 0) return;
        try {
            Context wc = createWindowContext(d,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager wm = wc.getSystemService(WindowManager.class);

            FrameLayout view = new FrameLayout(wc);
            view.setVisibility(View.INVISIBLE);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.alpha = 0f;
            lp.setBlurBehindRadius(0);
            lp.setTitle("FoldBlur");

            wm.addView(view, lp);

            Overlay o = new Overlay();
            o.wm = wm;
            o.view = view;
            o.lp = lp;
            overlays.put(id, o);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not add overlay on display " + id, e);
        }
    }

    private void removeOverlay(int id) {
        Overlay o = overlays.get(id);
        if (o == null) return;
        overlays.remove(id);
        try {
            o.wm.removeViewImmediate(o.view);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not remove overlay on display " + id, e);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        Display d = displays.getDisplay(displayId);
        if (d != null) addOverlay(d);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        removeOverlay(displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        long size = defaultDisplaySize();
        if (size != lastDefaultSize) {
            lastDefaultSize = size;
            // Backup mode: no hinge sensor, so play the timed animation when the screen switches.
            if (hinge == null) {
                engine.triggerPulse(SystemClock.uptimeMillis());
                scheduleFrame();
            }
        }
    }

    private long defaultDisplaySize() {
        Display d = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return -1;
        Display.Mode m = d.getMode();
        return ((long) m.getPhysicalWidth() << 32) | (m.getPhysicalHeight() & 0xffffffffL);
    }

    // ---------- Hinge sensor ----------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length == 0) return;
        engine.onAngle(event.values[0], SystemClock.uptimeMillis());
        scheduleFrame();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------- Drawing, once per screen refresh while the effect is running ----------

    private void scheduleFrame() {
        if (frameScheduled || stopping) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        long now = SystemClock.uptimeMillis();
        float strength = engine.update(now);
        if (engine.isActive(now)) {
            apply(strength);
            scheduleFrame();
        } else {
            apply(0f);
        }
    }

    private void apply(float s) {
        boolean visible = s > 0.002f;
        float alpha = visible ? MAX_WINDOW_ALPHA * EffectEngine.smoothstep(0f, 0.2f, s) : 0f;
        int radius = (visible && blurSupported) ? Math.round(s * maxBlur) : 0;
        // Without real blur (Power saving or "Reduce transparency" on), use a thicker frosted look.
        float tint = blurSupported ? frost : Math.max(frost, 0.6f);
        int a = visible ? Math.round(255f * tint * s) : 0;
        int color = dark ? Color.argb(a, 16, 16, 20) : Color.argb(a, 246, 248, 252);

        for (int i = 0; i < overlays.size(); i++) {
            draw(overlays.valueAt(i), visible, alpha, radius, color);
        }
    }

    private void draw(Overlay o, boolean visible, float alpha, int radius, int color) {
        if (o.drawnOnce && o.visible == visible && o.radius == radius && o.color == color
                && Math.abs(o.alpha - alpha) < 0.004f) {
            return; // nothing changed, skip work
        }
        o.drawnOnce = true;
        if (o.color != color) {
            o.view.setBackgroundColor(color);
            o.color = color;
        }
        if (o.visible != visible) {
            o.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            o.visible = visible;
        }
        o.alpha = alpha;
        o.radius = radius;
        o.lp.alpha = alpha;
        o.lp.setBlurBehindRadius(radius);
        try {
            o.wm.updateViewLayout(o.view, o.lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Overlay update failed", e);
        }
    }
}
