// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Mutable 3D vector. All methods work in place and return this. Zero allocation. */
public final class Vec3 {
    public float x;
    public float y;
    public float z;

    public Vec3() {
    }

    public Vec3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3 set(Vec3 v) {
        x = v.x;
        y = v.y;
        z = v.z;
        return this;
    }

    public Vec3 add(Vec3 v) {
        x += v.x;
        y += v.y;
        z += v.z;
        return this;
    }

    public Vec3 sub(Vec3 v) {
        x -= v.x;
        y -= v.y;
        z -= v.z;
        return this;
    }

    public Vec3 scale(float s) {
        x *= s;
        y *= s;
        z *= s;
        return this;
    }

    public float dot(Vec3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    /** this = a x b (safe if this is a or b). */
    public Vec3 cross(Vec3 a, Vec3 b) {
        float cx = a.y * b.z - a.z * b.y;
        float cy = a.z * b.x - a.x * b.z;
        float cz = a.x * b.y - a.y * b.x;
        x = cx;
        y = cy;
        z = cz;
        return this;
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 normalize() {
        float len = length();
        if (len > 0f) {
            float inv = 1f / len;
            x *= inv;
            y *= inv;
            z *= inv;
        }
        return this;
    }
}
