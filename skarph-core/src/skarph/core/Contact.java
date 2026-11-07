// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Result of a narrow-phase test: unit normal pointing from the first shape to the second, and the penetration depth. */
public final class Contact {
    public float nx;
    public float ny;
    public float nz;
    public float depth;
}
