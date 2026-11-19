// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** A 2D vector that works in place and returns {@code this}; it never allocates after construction. */
public final class Vec2 {
    public float x;
    public float y;

    public Vec2() {
    }

    public Vec2(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public Vec2 set(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Vec2 set(Vec2 v) {
        x = v.x;
        y = v.y;
        return this;
    }

    public Vec2 add(Vec2 v) {
        x += v.x;
        y += v.y;
        return this;
    }

    public Vec2 add(float dx, float dy) {
        x += dx;
        y += dy;
        return this;
    }

    public Vec2 sub(Vec2 v) {
        x -= v.x;
        y -= v.y;
        return this;
    }

    public Vec2 scale(float s) {
        x *= s;
        y *= s;
        return this;
    }

    public float dot(Vec2 v) {
        return x * v.x + y * v.y;
    }

    /** The z component of the 3D cross product: positive when v is counter-clockwise from this. */
    public float cross(Vec2 v) {
        return x * v.y - y * v.x;
    }

    public float lengthSq() {
        return x * x + y * y;
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }

    public float distanceTo(Vec2 v) {
        float dx = v.x - x;
        float dy = v.y - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Scales to length 1; a zero vector stays zero. */
    public Vec2 normalize() {
        float len = (float) Math.sqrt(x * x + y * y);
        if (len > 1e-12f) {
            float inv = 1f / len;
            x *= inv;
            y *= inv;
        }
        return this;
    }

    /** Rotates counter-clockwise by the angle in degrees. */
    public Vec2 rotate(float deg) {
        float c = FastMath.cos(deg);
        float s = FastMath.sin(deg);
        float nx = x * c - y * s;
        y = x * s + y * c;
        x = nx;
        return this;
    }

    /** Angle of the vector in degrees, -180..180 (0 = +x). */
    public float angle() {
        return FastMath.atan2(y, x);
    }

    /** this = a + (b - a) * t. */
    public Vec2 lerp(Vec2 a, Vec2 b, float t) {
        x = a.x + (b.x - a.x) * t;
        y = a.y + (b.y - a.y) * t;
        return this;
    }
}
