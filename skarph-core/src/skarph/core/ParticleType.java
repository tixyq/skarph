// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Look and physics of one kind of particle (spark, smoke, dust). Create once in init(), share between particles.
 * One type = one blend mode and (per frame) one texture, so a ParticleSystem batches well.
 * Public fields, plain data: set them after construction.
 */
public final class ParticleType {
    /** Frames chosen by particle age in ticks (frame by frame, no interpolation). */
    public final FrameAnimation animation;
    /** true = colors are added (fire, sparks, flares), false = alpha blending (smoke, dust). */
    public boolean additive;
    /** Added to the vertical speed every tick (world units per tick per tick). Negative = falls, positive = rises. */
    public float gravity;
    /** Speed multiplier per tick, 1 = no air resistance, 0.95 = slows down quickly. */
    public float drag = 1f;
    /** Billboard size (world units) at birth and at the end of life; the size follows the age. */
    public float sizeStart = 1f;
    public float sizeEnd = 1f;
    /** Tint. */
    public float r = 1f;
    public float g = 1f;
    public float b = 1f;
    /** Alpha at birth and at the end of life. For additive types the color is scaled by it (fades to black). */
    public float alphaStart = 1f;
    public float alphaEnd = 0f;

    public ParticleType(FrameAnimation animation) {
        if (animation == null) {
            throw new IllegalArgumentException("animation is null");
        }
        this.animation = animation;
    }

    /** One fixed region. Allocates: use in init(). */
    public ParticleType(TextureRegion region) {
        this(new FrameAnimation(new TextureRegion[] { region }, 1, true));
    }
}
