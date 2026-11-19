// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A physics body: a sphere, a vertical capsule or an axis-aligned box (nothing rotates).
 * Created by PhysicsWorld. Position and velocity may be read and written
 * directly. Units: distance units, velocity = units PER TICK, acceleration
 * = units per tick squared (the engine has no delta time).
 */
public final class Body {
    public float x;
    public float y;
    public float z;
    public float vx;
    public float vy;
    public float vz;

    /** Sphere radius (SPHERE bodies). */
    public float radius;
    /** Half length of the straight part of a CAPSULE body (the axis is vertical, y). Total half height = halfHeight + radius. */
    public float halfHeight;
    /** Box half extents (BOX bodies); for capsules hy = halfHeight + radius. */
    public float hx;
    public float hy;
    public float hz;

    /** 0 = static (never moves), otherwise 1 / mass. */
    public float invMass;
    /** Bounciness 0..1. Between two bodies the smaller value is used. */
    public float restitution = 0.5f;
    /** Free to use for your own purposes (for example an index into your arrays). */
    public int tag;

    /**
     * Collision layer bits of this body (default 1). A pair collides only if (a.layer &amp; b.mask) != 0 and
     * (b.layer &amp; a.mask) != 0.
     */
    public int layer = 1;
    /** Layers this body collides with (default -1 = all). */
    public int mask = -1;
    /**
     * A trigger never collides physically: it only reports PhysicsWorld listener events (onBegin / onEnd with
     * trigger = true) while another body overlaps it. Pickups, zones, sensors.
     */
    public boolean trigger;

    /** True if the body touched the floor during the last step. */
    public boolean onFloor;

    /** World-space box, refreshed every step. */
    public final Aabb bounds = new Aabb();

    int type;
    final int index;
    boolean active;
    int uid;
    boolean sleeping;
    int sleepTimer;
    boolean touched;
    float sleepX;
    float sleepY;
    float sleepZ;

    Body(int index) {
        this.index = index;
    }

    public int getType() {
        return type;
    }

    public boolean isActive() {
        return active;
    }

    /** True while the world has put this resting body to sleep (see PhysicsWorld.sleepingEnabled). */
    public boolean isSleeping() {
        return sleeping;
    }

    /** Wakes the body up (the world also wakes it when it is touched, moved or given a velocity). */
    public void wake() {
        sleeping = false;
        sleepTimer = 0;
    }

    public boolean isStatic() {
        return invMass == 0f;
    }

    public void updateBounds() {
        if (type == PhysicsWorld.SPHERE) {
            bounds.set(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        } else if (type == PhysicsWorld.CAPSULE) {
            float h = halfHeight + radius;
            bounds.set(x - radius, y - h, z - radius, x + radius, y + h, z + radius);
        } else {
            bounds.set(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz);
        }
    }
}
