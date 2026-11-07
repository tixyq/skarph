// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Automatic quality level for old devices. Watches the frame rate (frames counted over one second windows) and tells
 * the game to lower the level when it is too slow, or to raise it when there is plenty of room. Level 0 is the
 * lowest, levels - 1 the highest; what a level means (particles, objects, view distance) is up to the game.
 *
 * <pre>
 *   QualityGovernor gov = new QualityGovernor(3, 2);          // 3 levels, start at the highest
 *   // in render():
 *   if (gov.frame(engine.stats.nowNanos)) applyQuality(gov.getLevel());
 * </pre>
 * Rules (the fields below can be changed): after the start and after every change the first WARMUP_SECONDS seconds are
 * ignored (new meshes, textures and pools settle). Then FPS below lowerBelowFps for lowerSeconds seconds in a row lowers
 * the level by one; FPS at or above raiseAtFps for raiseSeconds seconds in a row raises it by one. When a level was
 * lowered, the level above it is remembered as too heavy for this device: the governor never goes back up to it
 * (no flip-flopping). reset() forgets that. No allocation.
 */
public final class QualityGovernor {
    public static final int WARMUP_SECONDS = 2;

    public int lowerBelowFps = 24;
    public int lowerSeconds = 3;
    public int raiseAtFps = 57;
    public int raiseSeconds = 10;

    private final int levels;
    private int level;
    private int ceiling;
    private long windowStart;
    private int frames;
    private int fps;
    private int warmup = WARMUP_SECONDS;
    private int slowRun;
    private int fastRun;

    public QualityGovernor(int levels, int startLevel) {
        if (levels < 1) {
            throw new IllegalArgumentException("levels < 1");
        }
        this.levels = levels;
        level = clamp(startLevel);
        ceiling = levels - 1;
    }

    private int clamp(int l) {
        return l < 0 ? 0 : (l >= levels ? levels - 1 : l);
    }

    public int getLevel() {
        return level;
    }

    /** Frames per second of the last full second window (0 before the first one). */
    public int getFps() {
        return fps;
    }

    /** The highest level the governor will still try (lowered after a level turned out too slow). */
    public int getCeiling() {
        return ceiling;
    }

    /** Sets the level by hand (for example from a menu); the warm up starts again. */
    public void setLevel(int l) {
        level = clamp(l);
        restartCounting();
    }

    /** Forget everything learned (the ceiling, the counters). */
    public void reset() {
        ceiling = levels - 1;
        restartCounting();
    }

    private void restartCounting() {
        warmup = WARMUP_SECONDS;
        slowRun = 0;
        fastRun = 0;
        windowStart = 0L;
        frames = 0;
    }

    /** Call once per rendered frame. @return true if the level changed (read getLevel()). */
    public boolean frame(long nowNanos) {
        if (windowStart == 0L) {
            windowStart = nowNanos;
            frames = 0;
            return false;
        }
        frames++;
        long elapsed = nowNanos - windowStart;
        if (elapsed < 1000000000L) {
            return false;
        }
        fps = (int) (frames * 1.0e9f / (float) elapsed + 0.5f);
        windowStart = nowNanos;
        frames = 0;
        if (warmup > 0) {
            warmup--;
            return false;
        }
        if (fps < lowerBelowFps) {
            fastRun = 0;
            slowRun++;
            if (slowRun >= lowerSeconds && level > 0) {
                ceiling = level - 1;
                level--;
                restartCounting();
                return true;
            }
        } else if (fps >= raiseAtFps) {
            slowRun = 0;
            fastRun++;
            if (fastRun >= raiseSeconds && level < ceiling) {
                level++;
                restartCounting();
                return true;
            }
        } else {
            slowRun = 0;
            fastRun = 0;
        }
        return false;
    }
}
