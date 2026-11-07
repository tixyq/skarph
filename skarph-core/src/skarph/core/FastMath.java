// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
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
}
