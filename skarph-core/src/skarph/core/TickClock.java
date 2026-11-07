// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Fixed-rate tick scheduler: exactly 60 logic ticks per second.
 * Logic never receives a delta time. All game/app time is counted in ticks.
 * Rendering is independent: slow frames run several ticks, fast frames run none.
 * Zero allocation.
 */
public final class TickClock {
    public static final int TICKS_PER_SECOND = 60;
    /** Protection against the "spiral of death" after long stalls. */
    public static final int MAX_CATCHUP_TICKS = 5;

    private static final long NANOS_PER_SECOND = 1000000000L;

    private long last;
    private long acc;          // accumulated time in units of (nanos * TICKS_PER_SECOND)
    private boolean started;
    private long totalTicks;

    /** Forget the previous timestamp (call after pause / long stalls). */
    public void reset() {
        started = false;
        acc = 0;
    }

    /** @return how many ticks must be executed now (0..MAX_CATCHUP_TICKS). */
    public int poll(long nowNanos) {
        if (!started) {
            started = true;
            last = nowNanos;
            acc = 0;
            return 0;
        }
        long elapsed = nowNanos - last;
        last = nowNanos;
        if (elapsed < 0) {
            elapsed = 0;
        }
        acc += elapsed * TICKS_PER_SECOND;
        int n = 0;
        while (acc >= NANOS_PER_SECOND && n < MAX_CATCHUP_TICKS) {
            acc -= NANOS_PER_SECOND;
            n++;
        }
        if (acc >= NANOS_PER_SECOND) {
            acc %= NANOS_PER_SECOND; // drop the excess, do not try to catch up
        }
        totalTicks += n;
        return n;
    }

    public long getTotalTicks() {
        return totalTicks;
    }
}
