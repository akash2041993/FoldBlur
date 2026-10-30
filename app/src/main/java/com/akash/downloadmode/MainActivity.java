package com.akash.downloadmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * One job: restart a Samsung phone straight into Download (Odin) mode,
 * the same thing "adb reboot download" does, but without a PC.
 *
 * A normal app is not allowed to reboot the phone, so the command runs
 * through Shizuku, which has shell (ADB) rights once started with
 * Wireless debugging.
 */
public class MainActivity extends Activity {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final int REQ_CODE = 7;

    private TextView statusView;
    private TextView logView;

    private final Shizuku.OnBinderReceivedListener onBinder = this::refresh;
    private final Shizuku.OnBinderDeadListener onDead = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener onPermission = (code, result) -> {
        refresh();
        if (code == REQ_CODE && result == PackageManager.PERMISSION_GRANTED) {
            confirmReboot();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addBinderReceivedListenerSticky(onBinder);
        Shizuku.addBinderDeadListener(onDead);
        Shizuku.addRequestPermissionResultListener(onPermission);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder);
        Shizuku.removeBinderDeadListener(onDead);
        Shizuku.removeRequestPermissionResultListener(onPermission);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0E1116"));
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(48), pad, pad);
        scroll.addView(col);

        TextView title = text("Download Mode", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        col.addView(text("Restart phone into Download (Odin) mode\nफ़ोन को Download mode में restart करें", 15,
                Color.parseColor("#9AA4B2")), lp(0, dp(4)));

        statusView = text("", 16, Color.WHITE);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusView.setBackground(round("#1B2230", 14));
        col.addView(statusView, lp(dp(24), 0));

        Button reboot = button("REBOOT TO DOWNLOAD MODE", "#1E88E5");
        reboot.setOnClickListener(v -> onRebootClicked());
        col.addView(reboot, lp(dp(20), 0));

        Button openShizuku = button("Open Shizuku", "#2A3342");
        openShizuku.setOnClickListener(v -> openShizuku());
        col.addView(openShizuku, lp(dp(12), 0));

        TextView help = text(
                "First time setup / पहली बार:\n"
                        + "1. Install \"Shizuku\" from Play Store.\n"
                        + "2. Connect phone to any Wi-Fi.\n"
                        + "3. Settings > Developer options > Wireless debugging ON.\n"
                        + "4. Open Shizuku > Start (Wireless debugging) and pair with the code.\n"
                        + "5. Come back here, tap the blue button, tap Allow.\n\n"
                        + "Phone will restart to the blue Download screen (\"Downloading... Do not turn off target\").\n"
                        + "Then connect USB to PC and use Odin.\n"
                        + "To exit Download mode: hold Volume Down + Power for 7 seconds.",
                14, Color.parseColor("#C9D1DB"));
        help.setLineSpacing(0, 1.2f);
        col.addView(help, lp(dp(24), 0));

        logView = text("", 12, Color.parseColor("#FFB74D"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        col.addView(logView, lp(dp(16), 0));

        setContentView(scroll);
    }

    private void refresh() {
        runOnUiThread(() -> {
            if (statusView == null) return;
            String s;
            if (!Shizuku.pingBinder()) {
                s = "✖ Shizuku is not running.\nShizuku चालू नहीं है — पहले Shizuku start करें।";
            } else if (Shizuku.isPreV11()) {
                s = "✖ Your Shizuku is too old. Update it from Play Store.";
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                s = "● Shizuku running. Tap the blue button and choose Allow.";
            } else {
                int uid = Shizuku.getUid();
                s = "✔ Ready. Shizuku running as " + (uid == 0 ? "root" : uid == 2000 ? "shell (ADB)" : "uid " + uid)
                        + ".\nतैयार है — blue button दबाएं।";
            }
            statusView.setText(s);
        });
    }

    // ------------------------------------------------------------ actions

    private void onRebootClicked() {
        if (!Shizuku.pingBinder()) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku not running")
                    .setMessage("Open Shizuku and start it with Wireless debugging first.\n\nपहले Shizuku खोलें और Start करें।")
                    .setPositiveButton("Open Shizuku", (d, w) -> openShizuku())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        if (Shizuku.isPreV11()) {
            refresh();
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                new AlertDialog.Builder(this)
                        .setTitle("Permission denied before")
                        .setMessage("Open Shizuku > Authorized applications and turn ON \"Download Mode\".")
                        .setPositiveButton("Open Shizuku", (d, w) -> openShizuku())
                        .setNegativeButton("Close", null)
                        .show();
            } else {
                Shizuku.requestPermission(REQ_CODE);
            }
            return;
        }
        confirmReboot();
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Restart into Download mode?")
                .setMessage("The phone will restart now. Save your work.\n\nफ़ोन अभी restart होगा।")
                .setPositiveButton("Reboot", (d, w) -> {
                    logView.setText("Sending reboot command…");
                    new Thread(this::doReboot, "reboot").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Tries the shell commands one by one. If one works, the phone restarts and nothing after it runs. */
    private void doReboot() {
        String[][] commands = {
                {"reboot", "download"},
                {"svc", "power", "reboot", "download"},
        };
        StringBuilder log = new StringBuilder();
        for (String[] cmd : commands) {
            String line = String.join(" ", cmd);
            try {
                Process p = newShizukuProcess(cmd);
                String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
                int exit = p.waitFor();
                log.append("$ ").append(line).append("  → exit ").append(exit).append('\n');
                if (!out.trim().isEmpty()) log.append(out.trim()).append('\n');
                // Shutdown can take a few seconds even when it worked.
                Thread.sleep(4000);
            } catch (Throwable t) {
                Throwable root = t.getCause() != null ? t.getCause() : t;
                log.append("$ ").append(line).append("  → ").append(root).append('\n');
            }
            log.append('\n');
            final String sofar = log.toString();
            runOnUiThread(() -> logView.setText(sofar));
        }
        final String result = "Phone did not restart. Send a screenshot of this:\n\n" + log;
        runOnUiThread(() -> logView.setText(result));
    }

    /** Shizuku.newProcess is private in API 13 but still works; it runs the command as the Shizuku (shell) user. */
    private static Process newShizukuProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        try {
            if (launch != null) {
                startActivity(launch);
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU_PKG)));
            }
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PKG)));
        }
    }

    // ------------------------------------------------------------ helpers

    private static String readAll(InputStream in) {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, String bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(58));
        b.setBackground(round(bg, 16));
        return b;
    }

    private GradientDrawable round(String color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
