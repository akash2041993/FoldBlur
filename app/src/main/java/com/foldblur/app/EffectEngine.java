package com.foldblur.app;

/**
 * Pure math for the fold effect. No Android classes here, so it can be tested on any computer.
 *
 * Idea: while the phone is moving between closed and open, the screen gets a strong blur.
 * As the phone gets close to fully open (or fully closed) the blur melts away, so the
 * content "comes into focus". If the hinge stops moving (for example the phone is held
 * half open like a laptop), the effect fades out so it never gets stuck on screen.
 */
public final class EffectEngine {

    // Hinge angles (degrees). 0 = closed, 180 = flat open.
    public float closedAngle = 10f;
    public float openAngle = 165f;

    // How long the effect stays after the hinge stops moving, then how long it takes to fade out.
    public long holdMs = 160;
    public long fadeMs = 220;

    // Smaller than this change (degrees) is treated as sensor noise, not movement.
    public float noiseDeg = 0.8f;

    // Length of the timed "pulse" animation used for the Test button and for phones without a hinge sensor.
    public long pulseMs = 650;

    public boolean effectWhenClosing = true;

    private boolean hasAngle = false;
    private float targetAngle = 0f;   // latest value from the sensor
    private float shownAngle = 0f;    // smoothed value used for drawing
    private float lastMotionAngle = 0f;
    private long lastMotionTime = Long.MIN_VALUE / 4;
    private int direction = 0;        // +1 opening, -1 closing

    private long pulseStart = Long.MIN_VALUE / 4;

    /** Call for every hinge sensor reading. */
    public void onAngle(float angle, long nowMs) {
        if (!hasAngle) {
            hasAngle = true;
            targetAngle = angle;
            shownAngle = angle;
            lastMotionAngle = angle;
            return; // first reading only sets the starting point
        }
        targetAngle = angle;
        float moved = angle - lastMotionAngle;
        if (Math.abs(moved) >= noiseDeg) {
            direction = moved > 0 ? 1 : -1;
            lastMotionAngle = angle;
            lastMotionTime = nowMs;
        }
    }

    /** Start the timed animation (Test button, or screen switch on phones without a hinge sensor). */
    public void triggerPulse(long nowMs) {
        pulseStart = nowMs;
    }

    /** Effect strength from 0 (nothing) to 1 (full blur). Call once per screen frame. */
    public float update(long nowMs) {
        // Smooth out coarse or jumpy sensor steps.
        shownAngle += (targetAngle - shownAngle) * 0.45f;
        if (Math.abs(targetAngle - shownAngle) < 0.05f) shownAngle = targetAngle;

        float hinge = 0f;
        if (hasAngle && (direction > 0 || effectWhenClosing)) {
            hinge = shape(progress(shownAngle)) * motion(nowMs);
        }
        return Math.max(hinge, pulse(nowMs));
    }

    /** True while frames still need to be drawn. */
    public boolean isActive(long nowMs) {
        boolean moving = nowMs - lastMotionTime < holdMs + fadeMs;
        boolean settling = Math.abs(targetAngle - shownAngle) > 0.05f;
        boolean pulsing = nowMs - pulseStart < pulseMs;
        return moving || settling || pulsing;
    }

    public float angle() {
        return shownAngle;
    }

    public boolean hasAngle() {
        return hasAngle;
    }

    // 0 at closed, 1 at open.
    float progress(float angle) {
        float span = openAngle - closedAngle;
        if (span <= 1f) return 0f;
        return clamp((angle - closedAngle) / span);
    }

    // Quick rise just after leaving "closed", flat in the middle, slow melt as it reaches "open".
    static float shape(float p) {
        float in = smoothstep(0f, 0.12f, p);
        float out = 1f - smoothstep(0.70f, 1f, p);
        return in * out;
    }

    // 1 while moving, holds briefly after movement stops, then fades to 0.
    float motion(long nowMs) {
        long since = nowMs - lastMotionTime;
        if (since <= holdMs) return 1f;
        if (fadeMs <= 0) return 0f;
        float t = (since - holdMs) / (float) fadeMs;
        return 1f - smoothstep(0f, 1f, t);
    }

    // Starts at full blur and melts away.
    float pulse(long nowMs) {
        long since = nowMs - pulseStart;
        if (since < 0 || since >= pulseMs || pulseMs <= 0) return 0f;
        float t = since / (float) pulseMs;
        return 1f - smoothstep(0f, 1f, t);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
