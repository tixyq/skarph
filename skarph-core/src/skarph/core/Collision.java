// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Narrow-phase tests between spheres and axis-aligned boxes.
 * Every method returns true on overlap and fills the Contact (normal points
 * from the first shape toward the second). Zero allocation.
 */
public final class Collision {
    private Collision() {
    }

    public static boolean sphereSphere(float ax, float ay, float az, float ar,
                                       float bx, float by, float bz, float br, Contact c) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float rs = ar + br;
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= rs * rs) {
            return false;
        }
        float dist = (float) Math.sqrt(d2);
        if (dist > 1e-6f) {
            float inv = 1f / dist;
            c.nx = dx * inv;
            c.ny = dy * inv;
            c.nz = dz * inv;
        } else {
            c.nx = 0f;
            c.ny = 1f;
            c.nz = 0f;
        }
        c.depth = rs - dist;
        return true;
    }

    /** Normal points from the sphere toward the box. */
    public static boolean sphereAabb(float cx, float cy, float cz, float r, Aabb box, Contact c) {
        float px = cx < box.minX ? box.minX : (cx > box.maxX ? box.maxX : cx);
        float py = cy < box.minY ? box.minY : (cy > box.maxY ? box.maxY : cy);
        float pz = cz < box.minZ ? box.minZ : (cz > box.maxZ ? box.maxZ : cz);
        float dx = px - cx;
        float dy = py - cy;
        float dz = pz - cz;
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > r * r) {
            return false;
        }
        if (d2 > 1e-12f) {
            float dist = (float) Math.sqrt(d2);
            float inv = 1f / dist;
            c.nx = dx * inv;
            c.ny = dy * inv;
            c.nz = dz * inv;
            c.depth = r - dist;
            return true;
        }
        // the sphere center is inside the box: leave through the nearest face
        float best = cx - box.minX;
        c.nx = 1f;
        c.ny = 0f;
        c.nz = 0f;
        float d = box.maxX - cx;
        if (d < best) {
            best = d;
            c.nx = -1f;
        }
        d = cy - box.minY;
        if (d < best) {
            best = d;
            c.nx = 0f;
            c.ny = 1f;
            c.nz = 0f;
        }
        d = box.maxY - cy;
        if (d < best) {
            best = d;
            c.nx = 0f;
            c.ny = -1f;
            c.nz = 0f;
        }
        d = cz - box.minZ;
        if (d < best) {
            best = d;
            c.nx = 0f;
            c.ny = 0f;
            c.nz = 1f;
        }
        d = box.maxZ - cz;
        if (d < best) {
            best = d;
            c.nx = 0f;
            c.ny = 0f;
            c.nz = -1f;
        }
        c.depth = best + r;
        return true;
    }

    /** Normal points from a toward b along the axis of least penetration. */
    public static boolean aabbAabb(Aabb a, Aabb b, Contact c) {
        float ox = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        float oy = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        float oz = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        if (ox <= 0f || oy <= 0f || oz <= 0f) {
            return false;
        }
        c.nx = 0f;
        c.ny = 0f;
        c.nz = 0f;
        if (ox <= oy && ox <= oz) {
            c.nx = b.centerX() >= a.centerX() ? 1f : -1f;
            c.depth = ox;
        } else if (oy <= oz) {
            c.ny = b.centerY() >= a.centerY() ? 1f : -1f;
            c.depth = oy;
        } else {
            c.nz = b.centerZ() >= a.centerZ() ? 1f : -1f;
            c.depth = oz;
        }
        return true;
    }

    /**
     * A vertical capsule (center cx, cy, cz, radius r, halfHeight hh) against a sphere. The normal points from the
     * capsule toward the sphere.
     */
    public static boolean capsuleSphere(float cx, float cy, float cz, float r, float hh,
                                        float sx, float sy, float sz, float sr, Contact c) {
        float py = sy < cy - hh ? cy - hh : (sy > cy + hh ? cy + hh : sy);
        return sphereSphere(cx, py, cz, r, sx, sy, sz, sr, c);
    }

    /** Two vertical capsules. The normal points from a toward b. */
    public static boolean capsuleCapsule(float ax, float ay, float az, float ar, float ah,
                                         float bx, float by, float bz, float br, float bh, Contact c) {
        float lo = Math.max(ay - ah, by - bh);
        float hi = Math.min(ay + ah, by + bh);
        float ya;
        float yb;
        if (lo <= hi) {
            ya = (lo + hi) * 0.5f;          // the axes overlap in height: compare at the same height
            yb = ya;
        } else if (ay > by) {
            ya = ay - ah;                   // a is above b: its lower end against b's upper end
            yb = by + bh;
        } else {
            ya = ay + ah;
            yb = by - bh;
        }
        return sphereSphere(ax, ya, az, ar, bx, yb, bz, br, c);
    }

    /** A vertical capsule against a box. The normal points from the capsule toward the box. */
    public static boolean capsuleAabb(float cx, float cy, float cz, float r, float hh, Aabb box, Contact c) {
        float lo = Math.max(cy - hh, box.minY);
        float hi = Math.min(cy + hh, box.maxY);
        float y;
        if (lo <= hi) {
            y = (lo + hi) * 0.5f;           // the axis passes the box in height: contact is sideways
        } else if (cy - hh > box.maxY) {
            y = cy - hh;                    // box below: the lower end
        } else {
            y = cy + hh;                    // box above: the upper end
        }
        return sphereAabb(cx, y, cz, r, box, c);
    }
}
