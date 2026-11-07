// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Result of PhysicsWorld.raycast(): the nearest body hit, the distance and the hit point. */
public final class RayHit {
    public Body body;
    public float t;
    public float x;
    public float y;
    public float z;
}
