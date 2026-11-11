// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Axis-aligned bounding box. Mutable, zero allocation. */
public final class Aabb {
    public float minX;
    public float minY;
    public float minZ;
    public float maxX;
    public float maxY;
    public float maxZ;

    public Aabb set(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        return this;
    }

    /** Box from its center and half extents. */
    public Aabb setCenterHalf(float cx, float cy, float cz, float hx, float hy, float hz) {
        return set(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    public boolean overlaps(Aabb o) {
        return minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    public float centerY() {
        return (minY + maxY) * 0.5f;
    }

    public float centerZ() {
        return (minZ + maxZ) * 0.5f;
    }
}
