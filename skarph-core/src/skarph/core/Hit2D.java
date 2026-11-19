// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Result of TileMap.raycast(): reusable. */
public final class Hit2D {
    /** Distance along the ray as a fraction 0..1 of the segment. */
    public float t;
    public float x;
    public float y;
    /** Normal of the face that was hit (one of them is 0). */
    public float nx;
    public float ny;
    /** The tile that was hit. */
    public int tileX;
    public int tileY;
}
