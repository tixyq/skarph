// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Frame-by-frame animation. Stateless: you keep your own tick counter and ask
 * for the frame at that tick, so one animation object can serve many sprites,
 * each with its own start offset. Time is measured in engine ticks (60 per second).
 */
public final class FrameAnimation {
    private final TextureRegion[] frames;
    private final int ticksPerFrame;
    private final boolean loop;

    public FrameAnimation(TextureRegion[] frames, int ticksPerFrame, boolean loop) {
        if (frames.length == 0 || ticksPerFrame < 1) {
            throw new IllegalArgumentException("need frames and ticksPerFrame >= 1");
        }
        this.frames = frames;
        this.ticksPerFrame = ticksPerFrame;
        this.loop = loop;
    }

    public TextureRegion getFrame(int ticks) {
        if (ticks < 0) {
            ticks = 0;
        }
        int i = ticks / ticksPerFrame;
        if (loop) {
            i %= frames.length;
        } else if (i >= frames.length) {
            i = frames.length - 1;
        }
        return frames[i];
    }

    /** Always false for looping animations. */
    public boolean isFinished(int ticks) {
        return !loop && ticks >= frames.length * ticksPerFrame;
    }

    public int getDurationTicks() {
        return frames.length * ticksPerFrame;
    }
}
