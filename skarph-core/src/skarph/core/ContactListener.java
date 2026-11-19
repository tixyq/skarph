// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Receives contact events of a PhysicsWorld (see PhysicsWorld.setListener). The calls happen at the END of
 * PhysicsWorld.step(), never in the middle of the simulation, so the game may create bodies and destroy them
 * (even the ones named in the call) inside the callbacks. A body named in onEnd may be inactive meanwhile
 * (Body.isActive()); do not keep the references after the call. Callbacks must not allocate.
 */
public interface ContactListener {
    /** Two bodies started to touch (or a body entered a trigger: {@code trigger} is true then). */
    void onBegin(Body a, Body b, boolean trigger);

    /** Two bodies stopped touching (or left a trigger). */
    void onEnd(Body a, Body b, boolean trigger);

    /**
     * A collision with closing speed above PhysicsWorld.hitSpeedThreshold: play a sound, spawn sparks. The normal points
     * from a to b, impactSpeed is in units per tick.
     */
    void onHit(Body a, Body b, float nx, float ny, float nz, float impactSpeed);
}
