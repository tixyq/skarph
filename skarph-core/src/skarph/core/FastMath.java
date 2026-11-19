// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Table based sine/cosine in DEGREES (much cheaper than Math.sin/cos on old
 * devices). 2048 table entries + linear interpolation: error below 2e-6.
 * Zero allocation (the table is built once when the class is loaded).
 */
public final class FastMath {
    private static final int N = 2048;
    private static final float SCALE = N / 360f;
    private static final float[] SIN = new float[N + 1];

    static {
        for (int i = 0; i <= N; i++) {
            SIN[i] = (float) Math.sin(i * (2.0 * Math.PI / N));
        }
    }

    private FastMath() {
    }

    public static float sin(float degrees) {
        float t = degrees * SCALE;
        int i = (int) t;
        float f = t - i;
        if (f < 0f) {
            f += 1f;
            i--;
        }
        i &= (N - 1);
        float a = SIN[i];
        return a + (SIN[i + 1] - a) * f;
    }

    public static float cos(float degrees) {
        return sin(degrees + 90f);
    }

    /** Linear interpolation: a at t = 0, b at t = 1. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 0 below e0, 1 above e1, a smooth S curve in between (requires e0 &lt; e1). */
    public static float smoothstep(float e0, float e1, float v) {
        float t = (v - e0) / (e1 - e0);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    /** Wraps an angle in degrees into -180..180. */
    public static float wrapAngle(float deg) {
        if (deg > -180f && deg <= 180f) {
            return deg;
        }
        deg -= 360f * (float) Math.floor((deg + 180f) / 360f);
        return deg;
    }

    /** Shortest signed turn from angle a to angle b, in degrees (-180..180). */
    public static float angleDelta(float a, float b) {
        return wrapAngle(b - a);
    }

    /** Arc tangent of y / x in DEGREES, -180..180, error below 0.001 degrees. No table, no allocation. */
    public static float atan2(float y, float x) {
        float ax = x < 0f ? -x : x;
        float ay = y < 0f ? -y : y;
        if (ax == 0f && ay == 0f) {
            return 0f;
        }
        boolean swap = ay > ax;
        float z = swap ? ax / ay : ay / ax;
        float z2 = z * z;
        float a = z * (0.99997726f + z2 * (-0.33262347f + z2 * (0.19354346f
                + z2 * (-0.11643287f + z2 * (0.05265332f - 0.0117212f * z2)))));
        a *= RAD_TO_DEG;
        if (swap) {
            a = 90f - a;
        }
        if (x < 0f) {
            a = 180f - a;
        }
        return y < 0f ? -a : a;
    }

    /** Arc cosine in degrees for v in -1..1 (clamped). */
    public static float acos(float v) {
        v = v < -1f ? -1f : (v > 1f ? 1f : v);
        return atan2((float) Math.sqrt(1f - v * v), v);
    }

    /** Arc sine in degrees for v in -1..1 (clamped). */
    public static float asin(float v) {
        v = v < -1f ? -1f : (v > 1f ? 1f : v);
        return atan2(v, (float) Math.sqrt(1f - v * v));
    }

    private static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

    /**
     * floor() as an int, without Math.floor (a native call that is slow on old Dalvik). Valid for |v| below about 2 billion.
     */
    public static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
