// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Frame time benchmark: average and minimum FPS and a score for a fixed run. The run itself is fixed by the caller
 * (the demo plays its cutscene once = the same number of logic ticks on every device); this class only measures the
 * time of the frames. No allocation.
 *
 * <pre>
 *   bench.start();                               // begin a run
 *   bench.frame(engine.stats.nowNanos);          // once per rendered frame, in render()
 *   bench.stop();                                // end of the run: the results are final now
 *   bench.getAvgFps(), bench.getMinFps(), bench.getScore()
 * </pre>
 * Average: frames divided by the time from the first to the last frame. Minimum: 1 / the longest single frame, not
 * counting the first WARMUP frames (the start of a run often hitches while textures and pools settle). Score =
 * (3 * average + minimum) * 25, rounded: a steady 60 FPS is 6000. Frames longer than one second count as one second.
 */
public final class Benchmark {
    /** Frames at the start of a run that are left out of the minimum. */
    public static final int WARMUP = 30;

    private boolean running;
    private int frames;
    private long first;
    private long last;
    private long longest;
    private float avgFps;
    private float minFps;
    private int score;

    public void start() {
        running = true;
        frames = 0;
        first = 0L;
        last = 0L;
        longest = 0L;
        avgFps = 0f;
        minFps = 0f;
        score = 0;
    }

    /** Aborts or ends the run; the results are computed from the frames seen so far. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (frames >= 2 && last > first) {
            avgFps = (frames - 1) * 1.0e9f / (float) (last - first);
            minFps = longest > 0L ? 1.0e9f / (float) longest : avgFps;
            if (minFps > avgFps) {
                minFps = avgFps;
            }
            score = (int) ((3f * avgFps + minFps) * 25f + 0.5f);
        }
    }

    /** Call once per frame while running (ignored when not running). */
    public void frame(long nowNanos) {
        if (!running) {
            return;
        }
        if (frames == 0) {
            first = nowNanos;
        } else {
            long dt = nowNanos - last;
            if (dt > 1000000000L) {
                dt = 1000000000L;
            }
            if (frames > WARMUP && dt > longest) {
                longest = dt;
            }
        }
        last = nowNanos;
        frames++;
    }

    public boolean isRunning() {
        return running;
    }

    public int getFrames() {
        return frames;
    }

    /** Seconds from the first to the last frame so far. */
    public float getSeconds() {
        return frames >= 2 ? (last - first) * 1.0e-9f : 0f;
    }

    /** Valid after stop(); 0 if fewer than 2 frames were seen. */
    public float getAvgFps() {
        return avgFps;
    }

    public float getMinFps() {
        return minFps;
    }

    public int getScore() {
        return score;
    }
}
