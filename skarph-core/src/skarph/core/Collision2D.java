// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Overlap tests between 2D shapes (boxes are centre + half sizes). Static, allocation free. */
public final class Collision2D {
    private Collision2D() {
    }

    public static boolean overlaps(float ax, float ay, float ahw, float ahh, float bx, float by, float bhw, float bhh) {
        float dx = ax - bx;
        float dy = ay - by;
        return (dx < 0f ? -dx : dx) < ahw + bhw && (dy < 0f ? -dy : dy) < ahh + bhh;
    }

    public static boolean circles(float ax, float ay, float ar, float bx, float by, float br) {
        float dx = ax - bx;
        float dy = ay - by;
        float r = ar + br;
        return dx * dx + dy * dy < r * r;
    }

    public static boolean circleBox(float cx, float cy, float r, float bx, float by, float bhw, float bhh) {
        float dx = cx - bx;
        float dy = cy - by;
        float qx = (dx < 0f ? -dx : dx) - bhw;
        float qy = (dy < 0f ? -dy : dy) - bhh;
        qx = qx < 0f ? 0f : qx;
        qy = qy < 0f ? 0f : qy;
        return qx * qx + qy * qy < r * r;
    }

    public static boolean pointInBox(float px, float py, float bx, float by, float bhw, float bhh) {
        float dx = px - bx;
        float dy = py - by;
        return (dx < 0f ? -dx : dx) <= bhw && (dy < 0f ? -dy : dy) <= bhh;
    }
}
