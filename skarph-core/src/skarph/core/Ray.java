// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Ray with a normalized direction. Mutable, zero allocation. All tests return the distance t along the ray, or -1 for a miss. */
public final class Ray {
    public float ox;
    public float oy;
    public float oz;
    public float dx;
    public float dy;
    public float dz;

    /** Sets origin and direction (the direction is normalized here). */
    public Ray set(float ox, float oy, float oz, float dx, float dy, float dz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0f) {
            float inv = 1f / len;
            dx *= inv;
            dy *= inv;
            dz *= inv;
        }
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        return this;
    }

    /** Writes the point at distance t into out. */
    public void pointAt(float t, Vec3 out) {
        out.x = ox + dx * t;
        out.y = oy + dy * t;
        out.z = oz + dz * t;
    }

    public float intersectSphere(float cx, float cy, float cz, float radius) {
        float lx = ox - cx;
        float ly = oy - cy;
        float lz = oz - cz;
        float b = lx * dx + ly * dy + lz * dz;
        float c = lx * lx + ly * ly + lz * lz - radius * radius;
        float disc = b * b - c;
        if (disc < 0f) {
            return -1f;
        }
        float root = (float) Math.sqrt(disc);
        float t = -b - root;
        if (t < 0f) {
            t = -b + root; // origin inside the sphere
        }
        return t < 0f ? -1f : t;
    }

    /** Slab test. Returns 0 if the origin is inside the box. */
    public float intersectAabb(Aabb box) {
        float tmin = -Float.MAX_VALUE;
        float tmax = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float o;
            float d;
            float lo;
            float hi;
            if (axis == 0) {
                o = ox; d = dx; lo = box.minX; hi = box.maxX;
            } else if (axis == 1) {
                o = oy; d = dy; lo = box.minY; hi = box.maxY;
            } else {
                o = oz; d = dz; lo = box.minZ; hi = box.maxZ;
            }
            if (d > -1e-9f && d < 1e-9f) {
                if (o < lo || o > hi) {
                    return -1f;
                }
            } else {
                float t1 = (lo - o) / d;
                float t2 = (hi - o) / d;
                if (t1 > t2) {
                    float tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                if (t1 > tmin) {
                    tmin = t1;
                }
                if (t2 < tmax) {
                    tmax = t2;
                }
                if (tmin > tmax) {
                    return -1f;
                }
            }
        }
        if (tmax < 0f) {
            return -1f;
        }
        return tmin > 0f ? tmin : 0f;
    }

    /** Intersection with the horizontal plane y = planeY. */
    public float intersectPlaneY(float planeY) {
        if (dy > -1e-9f && dy < 1e-9f) {
            return -1f;
        }
        float t = (planeY - oy) / dy;
        return t < 0f ? -1f : t;
    }
}
