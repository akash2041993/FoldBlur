package com.foldblur.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** The settings screen. Built in code so the project stays small. */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String OK = "✓  ";   // check mark
    private static final String NO = "✕  ";   // cross mark

    private LinearLayout root;
    private TextView angleText;
    private TextView statusText;
    private Button overlayBtn;
    private Button notifBtn;
    private Button batteryBtn;
    private Button captureBtn;
    private Switch effectSwitch;

    private static final int REQ_CAPTURE = 7;
    private boolean updatingUi;

    private SensorManager sensors;
    private Sensor hinge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensors = getSystemService(SensorManager.class);
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hinge != null) {
            sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    // ---------- Screen capture (real blur on Samsung) ----------

    private void requestCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent ask = Build.VERSION.SDK_INT >= 34
                // Only offer "Entire screen", the effect needs the whole screen.
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        try {
            startActivityForResult(ask, REQ_CAPTURE);
        } catch (RuntimeException e) {
            toast(getString(R.string.capture_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            toast(getString(R.string.capture_denied));
            refresh();
            return;
        }
        Prefs.setEnabled(this, true);
        startForegroundService(new Intent(this, FoldEffectService.class)
                .setAction(FoldEffectService.ACTION_CAPTURE)
                .putExtra(FoldEffectService.EXTRA_CODE, resultCode)
                .putExtra(FoldEffectService.EXTRA_DATA, data));
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 800);
    }

    // ---------- Screen layout ----------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, dp(40));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = text(getString(R.string.app_name), 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text(getString(R.string.tagline), 15));

        angleText = text("", 20);
        angleText.setTypeface(Typeface.DEFAULT_BOLD);
        angleText.setPadding(0, dp(16), 0, dp(4));
        root.addView(angleText);

        statusText = text("", 15);
        root.addView(statusText);

        header(getString(R.string.setup));

        overlayBtn = button(getString(R.string.step_overlay), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        notifBtn = button(getString(R.string.step_notif), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            }
        });

        batteryBtn = button(getString(R.string.step_battery), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        header(getString(R.string.effect));

        effectSwitch = new Switch(this);
        effectSwitch.setText(getString(R.string.effect_on));
        effectSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        effectSwitch.setPadding(0, dp(8), 0, dp(8));
        effectSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!updatingUi) setEffect(on);
            }
        });
        root.addView(effectSwitch);

        captureBtn = button(getString(R.string.step_capture), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    toast(getString(R.string.need_overlay));
                    return;
                }
                requestCapture();
            }
        });

        button(getString(R.string.test), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!FoldEffectService.running) {
                    toast(getString(R.string.turn_on_first));
                    return;
                }
                startService(new Intent(MainActivity.this, FoldEffectService.class)
                        .setAction(FoldEffectService.ACTION_TEST));
            }
        });

        header(getString(R.string.look));
        slider(getString(R.string.blur_strength), Prefs.BLUR, 20, 150, Prefs.DEFAULT_BLUR, "");
        slider(getString(R.string.frost), Prefs.FROST, 0, 60, Prefs.DEFAULT_FROST, "%");
        toggle(getString(R.string.dark_glass), Prefs.DARK, false);
        toggle(getString(R.string.on_close), Prefs.ON_CLOSE, true);

        header(getString(R.string.fine_tune));
        root.addView(text(getString(R.string.fine_tune_help), 14));
        slider(getString(R.string.start_angle), Prefs.CLOSED_ANGLE, 0, 60,
                Prefs.DEFAULT_CLOSED_ANGLE, "°");
        slider(getString(R.string.end_angle), Prefs.OPEN_ANGLE, 100, 180,
                Prefs.DEFAULT_OPEN_ANGLE, "°");

        header(getString(R.string.keep_capture));
        root.addView(text(getString(R.string.keep_capture_help), 14));
        button(getString(R.string.open_dev_options), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            }
        });

        header(getString(R.string.help));
        root.addView(text(getString(R.string.help_text), 14));
    }

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean blurOk = getSystemService(WindowManager.class).isCrossWindowBlurEnabled();

        StringBuilder s = new StringBuilder();
        s.append(hinge != null ? OK + getString(R.string.hinge_ok) : NO + getString(R.string.hinge_missing)).append('\n');
        boolean capturing = FoldEffectService.capturing;
        s.append(capturing ? OK + getString(R.string.capture_on) : NO + getString(R.string.capture_off)).append('\n');
        s.append(blurOk ? OK + getString(R.string.blur_ok) : NO + getString(R.string.blur_off)).append('\n');
        s.append(overlayOk ? OK : NO).append(getString(R.string.status_overlay)).append('\n');
        s.append(notifOk ? OK : NO).append(getString(R.string.status_notif)).append('\n');
        s.append(batteryOk ? OK : NO).append(getString(R.string.status_battery));
        statusText.setText(s.toString());

        if (hinge == null) angleText.setText(getString(R.string.no_angle));

        overlayBtn.setVisibility(overlayOk ? View.GONE : View.VISIBLE);
        notifBtn.setVisibility(notifOk ? View.GONE : View.VISIBLE);
        batteryBtn.setVisibility(batteryOk ? View.GONE : View.VISIBLE);
        captureBtn.setVisibility(capturing ? View.GONE : View.VISIBLE);

        updatingUi = true;
        effectSwitch.setChecked(Prefs.enabled(this) && overlayOk);
        updatingUi = false;
    }

    private void setEffect(boolean on) {
        Intent svc = new Intent(this, FoldEffectService.class);
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                toast(getString(R.string.need_overlay));
                updatingUi = true;
                effectSwitch.setChecked(false);
                updatingUi = false;
                return;
            }
            Prefs.setEnabled(this, true);
            startForegroundService(svc);
            // Real blur on Samsung needs screen capture, so ask right away.
            if (!FoldEffectService.capturing) requestCapture();
        } else {
            Prefs.setEnabled(this, false);
            stopService(svc);
        }
    }

    // ---------- Live hinge angle ----------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length == 0) return;
        angleText.setText(getString(R.string.angle, Math.round(event.values[0])));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------- Small helpers ----------

    private void slider(final String label, final String key, final int min, final int max,
                        int def, final String unit) {
        final SharedPreferences p = Prefs.get(this);
        final TextView t = text("", 15);
        t.setPadding(0, dp(12), 0, 0);
        root.addView(t);

        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        int value = Math.max(min, Math.min(max, p.getInt(key, def)));
        bar.setProgress(value);
        t.setText(label + ": " + value + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                t.setText(label + ": " + v + unit);
                if (fromUser) p.edit().putInt(key, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(bar);
    }

    private void toggle(String label, final String key, boolean def) {
        final SharedPreferences p = Prefs.get(this);
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(p.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                p.edit().putBoolean(key, on).apply();
            }
        });
        root.addView(sw);
    }

    private void header(String s) {
        TextView t = text(s, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(24), 0, dp(6));
        root.addView(t);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(b, lp);
        return b;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0f, 1.2f);
        return t;
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
