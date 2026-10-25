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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Runs in the background. Reads the hinge angle sensor and shows a blur over the screen
 * while the phone is being opened or closed.
 *
 * Two ways to blur:
 *  1. Picture mode (works on Samsung): a low-resolution live copy of the screen is kept using
 *     screen capture. When the hinge starts moving, that picture is frozen, blurred inside this
 *     app and shown on top, then it fades away as the phone reaches fully open.
 *  2. Glass mode: Android's window blur, used when screen capture is not allowed. Samsung turns
 *     this off on some phones, then only a frosted tint is shown.
 */
public class FoldEffectService extends Service implements
        SensorEventListener,
        Choreographer.FrameCallback,
        DisplayManager.DisplayListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        ImageReader.OnImageAvailableListener {

    static final String ACTION_TEST = "com.foldblur.app.TEST";
    static final String ACTION_STOP = "com.foldblur.app.STOP";
    static final String ACTION_CAPTURE = "com.foldblur.app.CAPTURE";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    static volatile boolean running;
    static volatile boolean capturing;

    private static final String TAG = "FoldBlur";
    private static final String CHANNEL = "effect";
    private static final int NOTIF_ID = 1;

    // Android lets touches pass through an overlay only up to this opacity.
    private static final float GLASS_MAX_ALPHA = 0.8f;
    // Capture the screen at 1/4 size. It gets blurred anyway, and small is fast.
    private static final int CAPTURE_DIVIDER = 4;

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

    // Screen capture (picture mode)
    private final Object imageLock = new Object();
    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private volatile ImageReader reader;
    private Image heldImage;                 // newest captured frame, guarded by imageLock
    private volatile boolean overlayShowing;  // don't keep frames that contain our own blur
    private int captureW;
    private int captureH;

    private boolean showing;
    private Bitmap picture;

    private final Consumer<Boolean> blurListener = new Consumer<Boolean>() {
        @Override
        public void accept(Boolean enabled) {
            blurSupported = enabled != null && enabled;
        }
    };

    private final Runnable resizeCaptureLater = new Runnable() {
        @Override
        public void run() {
            resizeCapture();
        }
    };

    private static final class Overlay {
        WindowManager wm;
        FrameLayout root;
        ImageView image;
        WindowManager.LayoutParams lp;
        ColorDrawable tint;
        boolean drawnOnce;
        boolean visible;
        float alpha;
        int behindRadius;
        int imageRadius = -1;
        float scale = 1f;
        int color;
    }

    // ================= Lifecycle =================

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());

        if (!goForeground(false) || !Settings.canDrawOverlays(this)) {
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
            if (ACTION_CAPTURE.equals(action)) {
                startCapture(intent.getIntExtra(EXTRA_CODE, 0), captureData(intent));
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
        if (handler != null) handler.removeCallbacks(resizeCaptureLater);
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            frameScheduled = false;
        }
        stopCapture();
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
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

    // ================= Notification (Android requires one for background services) =================

    private boolean goForeground(boolean withCapture) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Notification n = buildNotification(withCapture
                ? getString(R.string.notif_text) : getString(R.string.notif_no_capture));
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (withCapture) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(NOTIF_ID, n, type);
            } else if (withCapture) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start in foreground", e);
            return false;
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FoldEffectService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat),
                        getString(R.string.stop), stop).build())
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    // ================= Settings =================

    private void loadPrefs() {
        SharedPreferences p = Prefs.get(this);
        maxBlur = Math.min(150, p.getInt(Prefs.BLUR, Prefs.DEFAULT_BLUR));
        frost = p.getInt(Prefs.FROST, Prefs.DEFAULT_FROST) / 100f;
        dark = p.getBoolean(Prefs.DARK, false);
        engine.effectWhenClosing = p.getBoolean(Prefs.ON_CLOSE, true);
        engine.closedAngle = p.getInt(Prefs.CLOSED_ANGLE, Prefs.DEFAULT_CLOSED_ANGLE);
        engine.openAngle = p.getInt(Prefs.OPEN_ANGLE, Prefs.DEFAULT_OPEN_ANGLE);
        for (int i = 0; i < overlays.size(); i++) {
            overlays.valueAt(i).imageRadius = -1; // redraw blur with new strength
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        loadPrefs();
        if (Prefs.ENABLED.equals(key) && !sp.getBoolean(Prefs.ENABLED, false) && !stopping) {
            stopping = true;
            stopSelf();
        }
    }

    // ================= Screen capture =================

    @SuppressWarnings("deprecation")
    private static Intent captureData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private void startCapture(int resultCode, Intent data) {
        if (data == null) return;
        if (projection != null) {
            stopCapture();
            projection.stop();
            projection = null;
        }
        // Android 14+: the service must be a "screen capture" service before it may capture.
        if (!goForeground(true)) return;
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "Screen capture not allowed", e);
            projection = null;
        }
        if (projection == null) {
            onCaptureLost();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        projection = null;
                        stopCapture();
                        onCaptureLost();
                    }
                });
            }
        }, handler);

        if (captureThread == null) {
            captureThread = new HandlerThread("FoldBlurCapture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
        }

        Rect b = screenBounds();
        captureW = Math.max(1, b.width() / CAPTURE_DIVIDER);
        captureH = Math.max(1, b.height() / CAPTURE_DIVIDER);
        ImageReader r = newReader(captureW, captureH);
        try {
            virtualDisplay = projection.createVirtualDisplay("FoldBlur", captureW, captureH,
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.getSurface(), null, handler);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start screen capture", e);
            r.close();
            projection.stop();
            projection = null;
            onCaptureLost();
            return;
        }
        reader = r;
        capturing = true;
        updateNotification(getString(R.string.notif_text));
    }

    private void onCaptureLost() {
        capturing = false;
        picture = null;
        goForeground(false);
        updateNotification(getString(R.string.notif_capture_stopped));
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(this, captureHandler);
        return r;
    }

    private void stopCapture() {
        capturing = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        final ImageReader old = reader;
        reader = null;
        synchronized (imageLock) {
            if (heldImage != null) {
                heldImage.close();
                heldImage = null;
            }
        }
        if (old != null) {
            if (captureHandler != null) {
                captureHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        old.close();
                    }
                });
            } else {
                old.close();
            }
        }
    }

    /** The screen changed size (cover to inner screen, or rotation): capture at the new size. */
    private void resizeCapture() {
        if (virtualDisplay == null || reader == null) return;
        Rect b = screenBounds();
        int w = Math.max(1, b.width() / CAPTURE_DIVIDER);
        int h = Math.max(1, b.height() / CAPTURE_DIVIDER);
        if (w == captureW && h == captureH) return;
        captureW = w;
        captureH = h;
        final ImageReader old = reader;
        ImageReader r = newReader(w, h);
        try {
            virtualDisplay.resize(w, h, getResources().getDisplayMetrics().densityDpi);
            virtualDisplay.setSurface(r.getSurface());
        } catch (RuntimeException e) {
            Log.w(TAG, "Capture resize failed", e);
        }
        reader = r;
        synchronized (imageLock) {
            if (heldImage != null) {
                heldImage.close();
                heldImage = null;
            }
        }
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                old.close();
            }
        });
    }

    private Rect screenBounds() {
        Overlay o = overlays.get(Display.DEFAULT_DISPLAY);
        if (o != null) {
            try {
                return o.wm.getMaximumWindowMetrics().getBounds();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return new Rect(0, 0, m.widthPixels, m.heightPixels);
    }

    /** Runs on the capture thread for every new screen frame. Keeps only the newest one. */
    @Override
    public void onImageAvailable(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (RuntimeException e) {
            return;
        }
        if (img == null) return;
        synchronized (imageLock) {
            if (r != reader || overlayShowing) {
                img.close();
                return;
            }
            if (heldImage != null) heldImage.close();
            heldImage = img;
        }
    }

    /** Copies the newest captured frame into a Bitmap (main thread, a few milliseconds). */
    private Bitmap takePicture() {
        synchronized (imageLock) {
            if (heldImage == null) return null;
            try {
                int w = heldImage.getWidth();
                int h = heldImage.getHeight();
                Image.Plane plane = heldImage.getPlanes()[0];
                ByteBuffer src = plane.getBuffer();
                int rowStride = plane.getRowStride();
                int rowBytes = w * 4;
                byte[] row = new byte[rowBytes];
                ByteBuffer dst = ByteBuffer.allocate(rowBytes * h);
                for (int y = 0; y < h; y++) {
                    src.position(y * rowStride);
                    src.get(row, 0, rowBytes);
                    dst.put(row);
                }
                dst.rewind();
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(dst);
                return bmp;
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not read captured frame", e);
                return null;
            }
        }
    }

    // ================= Overlay windows (one per screen) =================

    private void addOverlay(Display d) {
        int id = d.getDisplayId();
        if (overlays.get(id) != null) return;
        if (id != Display.DEFAULT_DISPLAY && (d.getFlags() & Display.FLAG_PRIVATE) != 0) return;
        if (virtualDisplay != null && virtualDisplay.getDisplay() != null
                && virtualDisplay.getDisplay().getDisplayId() == id) return;
        try {
            Context wc = createWindowContext(d,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager wm = wc.getSystemService(WindowManager.class);

            FrameLayout root = new FrameLayout(wc);
            root.setVisibility(View.INVISIBLE);
            ImageView image = new ImageView(wc);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ColorDrawable tint = new ColorDrawable(Color.TRANSPARENT);
            root.setForeground(tint);

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

            wm.addView(root, lp);

            Overlay o = new Overlay();
            o.wm = wm;
            o.root = root;
            o.image = image;
            o.lp = lp;
            o.tint = tint;
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
            o.wm.removeViewImmediate(o.root);
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
        if (virtualDisplay != null) {
            handler.removeCallbacks(resizeCaptureLater);
            handler.postDelayed(resizeCaptureLater, 300);
        }
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

    // ================= Hinge sensor =================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length == 0) return;
        engine.onAngle(event.values[0], SystemClock.uptimeMillis());
        scheduleFrame();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ================= Drawing, once per screen refresh while the effect runs =================

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

        if (visible && !showing) {
            // Effect starts: freeze the newest screen picture before our blur covers it.
            showing = true;
            picture = capturing ? takePicture() : null;
            overlayShowing = true;
            for (int i = 0; i < overlays.size(); i++) {
                overlays.valueAt(i).image.setImageBitmap(picture);
            }
        } else if (!visible && showing) {
            showing = false;
            overlayShowing = false;
            picture = null;
            for (int i = 0; i < overlays.size(); i++) {
                overlays.valueAt(i).image.setImageBitmap(null);
            }
        }

        float alpha;
        int behind;
        int imageRadius;
        float scale;
        float tintAmount;
        if (picture != null) {
            // Picture mode: blurred copy of the screen, floating slightly forward, fading out.
            alpha = visible ? EffectEngine.smoothstep(0f, 0.55f, s) : 0f;
            behind = 0;
            imageRadius = visible ? Math.round(maxBlur * 0.5f * (0.6f + 0.4f * s)) : 0;
            scale = 1f + 0.06f * s;
            tintAmount = frost * s;
        } else {
            // Glass mode: Android window blur, or only a frosted tint when that is turned off.
            alpha = visible ? GLASS_MAX_ALPHA * EffectEngine.smoothstep(0f, 0.2f, s) : 0f;
            behind = (visible && blurSupported) ? Math.round(s * maxBlur) : 0;
            imageRadius = 0;
            scale = 1f;
            tintAmount = (blurSupported ? frost : Math.max(frost, 0.6f)) * s;
        }
        int a = visible ? Math.round(255f * Math.min(1f, tintAmount)) : 0;
        int color = dark ? Color.argb(a, 16, 16, 20) : Color.argb(a, 246, 248, 252);

        for (int i = 0; i < overlays.size(); i++) {
            draw(overlays.valueAt(i), visible, alpha, behind, imageRadius, scale, color);
        }
    }

    private void draw(Overlay o, boolean visible, float alpha, int behind, int imageRadius,
                      float scale, int color) {
        if (o.drawnOnce && o.visible == visible && o.behindRadius == behind
                && o.imageRadius == imageRadius && o.color == color
                && Math.abs(o.alpha - alpha) < 0.004f && Math.abs(o.scale - scale) < 0.001f) {
            return; // nothing changed, skip work
        }
        o.drawnOnce = true;
        if (o.color != color) {
            o.tint.setColor(color);
            o.color = color;
        }
        if (o.imageRadius != imageRadius) {
            o.image.setRenderEffect(imageRadius >= 1
                    ? RenderEffect.createBlurEffect(imageRadius, imageRadius, Shader.TileMode.CLAMP)
                    : null);
            o.imageRadius = imageRadius;
        }
        if (o.scale != scale) {
            o.image.setScaleX(scale);
            o.image.setScaleY(scale);
            o.scale = scale;
        }
        if (o.visible != visible) {
            o.root.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            o.visible = visible;
        }
        boolean windowChanged = Math.abs(o.alpha - alpha) >= 0.004f || o.behindRadius != behind
                || !visible;
        if (!windowChanged) return;
        o.alpha = alpha;
        o.behindRadius = behind;
        o.lp.alpha = alpha;
        o.lp.setBlurBehindRadius(behind);
        try {
            o.wm.updateViewLayout(o.root, o.lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Overlay update failed", e);
        }
    }
}
