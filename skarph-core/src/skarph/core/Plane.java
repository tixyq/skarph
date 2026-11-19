// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** A plane n . p + d = 0 with a unit normal. Mutable, zero allocation. The normal points to the "front" (positive) side. */
public final class Plane {
    public float nx;
    public float ny = 1f;
    public float nz;
    public float d;

    /** Plane through a point with the given normal (normalized here; a zero normal gives y up). */
    public Plane set(float px, float py, float pz, float nx, float ny, float nz) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            nx = 0f;
            ny = 1f;
            nz = 0f;
            len = 1f;
        }
        float inv = 1f / len;
        this.nx = nx * inv;
        this.ny = ny * inv;
        this.nz = nz * inv;
        this.d = -(this.nx * px + this.ny * py + this.nz * pz);
        return this;
    }

    /** Plane through three points, counter-clockwise seen from the front. */
    public Plane setFromPoints(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        return set(ax, ay, az, uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
    }

    /** Signed distance of a point: positive in front, negative behind. */
    public float distance(float x, float y, float z) {
        return nx * x + ny * y + nz * z + d;
    }

    /** Distance along the ray to the plane, or -1 if the ray is parallel or points away. */
    public float raycast(Ray ray) {
        float denom = nx * ray.dx + ny * ray.dy + nz * ray.dz;
        if (denom > -1e-9f && denom < 1e-9f) {
            return -1f;
        }
        float t = -(nx * ray.ox + ny * ray.oy + nz * ray.oz + d) / denom;
        return t >= 0f ? t : -1f;
    }

    /** -1 box completely behind, +1 completely in front, 0 crossing the plane. */
    public int classify(Aabb b) {
        float cx = (b.minX + b.maxX) * 0.5f, cy = (b.minY + b.maxY) * 0.5f, cz = (b.minZ + b.maxZ) * 0.5f;
        float ex = (b.maxX - b.minX) * 0.5f, ey = (b.maxY - b.minY) * 0.5f, ez = (b.maxZ - b.minZ) * 0.5f;
        float r = ex * (nx < 0f ? -nx : nx) + ey * (ny < 0f ? -ny : ny) + ez * (nz < 0f ? -nz : nz);
        float s = distance(cx, cy, cz);
        return s > r ? 1 : (s < -r ? -1 : 0);
    }
}
