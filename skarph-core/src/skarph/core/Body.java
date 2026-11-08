// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A physics body: a sphere or an axis-aligned box (boxes never rotate).
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
    /** Box half extents (BOX bodies). */
    public float hx;
    public float hy;
    public float hz;

    /** 0 = static (never moves), otherwise 1 / mass. */
    public float invMass;
    /** Bounciness 0..1. Between two bodies the smaller value is used. */
    public float restitution = 0.5f;
    /** Free to use for your own purposes (for example an index into your arrays). */
    public int tag;

    /** True if the body touched the floor during the last step. */
    public boolean onFloor;

    /** World-space box, refreshed every step. */
    public final Aabb bounds = new Aabb();

    int type;
    final int index;
    boolean active;

    Body(int index) {
        this.index = index;
    }

    public int getType() {
        return type;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isStatic() {
        return invMass == 0f;
    }

    public void updateBounds() {
        if (type == PhysicsWorld.SPHERE) {
            bounds.set(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        } else {
            bounds.set(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz);
        }
    }
}
