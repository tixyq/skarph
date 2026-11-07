// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Frames-per-second and ticks-per-second counters, updated once per second. */
public final class Stats {
    public int fps;
    public int tps;
    /** Set to true every time fps/tps were refreshed. The reader clears it. */
    public boolean changed;
    /** Time stamp of the current frame (the value given to Engine.frame()), for code that needs frame times, e.g. Benchmark. */
    public long nowNanos;

    private long windowStart;
    private int frames;
    private int ticks;

    void frame(long nowNanos, int ticksRun) {
        this.nowNanos = nowNanos;
        if (windowStart == 0) {
            windowStart = nowNanos;
        }
        frames++;
        ticks += ticksRun;
        if (nowNanos - windowStart >= 1000000000L) {
            fps = frames;
            tps = ticks;
            frames = 0;
            ticks = 0;
            windowStart = nowNanos;
            changed = true;
        }
    }
}
