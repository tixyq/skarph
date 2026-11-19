// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Easing curves: map progress t (0..1) to a shaped progress (0..1, some overshoot). Static, allocation free, the curve
 * is chosen by an int constant so it can be stored in a field or a data file. Typical use: {@code Ease.apply(Ease.OUT_CUBIC, t)}
 * or the stateful {@link Tween}.
 */
public final class Ease {
    public static final int LINEAR = 0;
    public static final int IN_QUAD = 1;
    public static final int OUT_QUAD = 2;
    public static final int IN_OUT_QUAD = 3;
    public static final int IN_CUBIC = 4;
    public static final int OUT_CUBIC = 5;
    public static final int IN_OUT_CUBIC = 6;
    public static final int IN_SINE = 7;
    public static final int OUT_SINE = 8;
    public static final int IN_OUT_SINE = 9;
    public static final int IN_EXPO = 10;
    public static final int OUT_EXPO = 11;
    public static final int IN_BACK = 12;
    public static final int OUT_BACK = 13;
    public static final int OUT_ELASTIC = 14;
    public static final int OUT_BOUNCE = 15;
    /** Smoothstep: 3t^2 - 2t^3. */
    public static final int SMOOTH = 16;
    /** Smootherstep: 6t^5 - 15t^4 + 10t^3. */
    public static final int SMOOTHER = 17;
    /** Number of curves (valid ids are 0 .. COUNT - 1). */
    public static final int COUNT = 18;

    private Ease() {
    }

    /** Shaped progress for t, clamped to 0..1 first. Unknown ids give LINEAR. */
    public static float apply(int type, float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        switch (type) {
            case IN_QUAD:
                return t * t;
            case OUT_QUAD:
                return t * (2f - t);
            case IN_OUT_QUAD:
                return t < 0.5f ? 2f * t * t : -1f + (4f - 2f * t) * t;
            case IN_CUBIC:
                return t * t * t;
            case OUT_CUBIC: {
                float u = t - 1f;
                return u * u * u + 1f;
            }
            case IN_OUT_CUBIC:
                if (t < 0.5f) {
                    return 4f * t * t * t;
                } else {
                    float u = 2f * t - 2f;
                    return 0.5f * u * u * u + 1f;
                }
            case IN_SINE:
                return 1f - FastMath.cos(t * 90f);
            case OUT_SINE:
                return FastMath.sin(t * 90f);
            case IN_OUT_SINE:
                return 0.5f * (1f - FastMath.cos(t * 180f));
            case IN_EXPO:
                return (float) Math.pow(2.0, 10.0 * (t - 1.0));
            case OUT_EXPO:
                return 1f - (float) Math.pow(2.0, -10.0 * t);
            case IN_BACK:
                return t * t * (2.70158f * t - 1.70158f);
            case OUT_BACK: {
                float u = t - 1f;
                return 1f + u * u * (2.70158f * u + 1.70158f);
            }
            case OUT_ELASTIC:
                return (float) Math.pow(2.0, -10.0 * t) * FastMath.sin((t * 10f - 0.75f) * 120f) + 1f;
            case OUT_BOUNCE:
                if (t < 1f / 2.75f) {
                    return 7.5625f * t * t;
                } else if (t < 2f / 2.75f) {
                    float u = t - 1.5f / 2.75f;
                    return 7.5625f * u * u + 0.75f;
                } else if (t < 2.5f / 2.75f) {
                    float u = t - 2.25f / 2.75f;
                    return 7.5625f * u * u + 0.9375f;
                } else {
                    float u = t - 2.625f / 2.75f;
                    return 7.5625f * u * u + 0.984375f;
                }
            case SMOOTH:
                return t * t * (3f - 2f * t);
            case SMOOTHER:
                return t * t * t * (t * (t * 6f - 15f) + 10f);
            default:
                return t;
        }
    }
}
