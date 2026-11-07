// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Frame statistics as ready-to-draw text, without allocations. Call frame() at the end of
 * render(), after everything was drawn, then draw getText() with a BitmapFont.
 * The garbage collector count comes from the platform (see setGcCount); without it GC shows 0.
 */
public final class Profiler {
    private final TextBuffer text = new TextBuffer(96);
    private int maxDrawCalls;
    private int maxTriangles;
    private int drawCalls;
    private int triangles;
    private int gcTotal;
    private int gcBase;
    private boolean gcBaseSet;
    private int shownFps = -1;
    private int shownTps = -1;
    private int shownCalls = -1;
    private int shownTris = -1;
    private int shownGc = -1;

    /** Total number of GC runs of the process so far. Call every frame with the platform value. */
    public void setGcCount(int total) {
        gcTotal = total;
        if (!gcBaseSet) {
            gcBase = total;
            gcBaseSet = true;
        }
    }

    /** Reads the counters of the frame that was just drawn and refreshes the text if needed. */
    public void frame(Stats stats, Renderer r) {
        drawCalls = r.getDrawCalls();
        triangles = r.getTriangles();
        if (drawCalls > maxDrawCalls) {
            maxDrawCalls = drawCalls;
        }
        if (triangles > maxTriangles) {
            maxTriangles = triangles;
        }
        int gc = getGcCount();
        if (stats.fps != shownFps || stats.tps != shownTps || drawCalls != shownCalls
                || triangles != shownTris || gc != shownGc) {
            shownFps = stats.fps;
            shownTps = stats.tps;
            shownCalls = drawCalls;
            shownTris = triangles;
            shownGc = gc;
            text.clear().append("FPS ").append(shownFps).append("  TPS ").append(shownTps).append('\n');
            text.append("DRAW ").append(drawCalls).append("  TRI ").append(triangles).append('\n');
            text.append("GC ").append(gc);
        }
    }

    /** Three lines: FPS and TPS, draw calls and triangles, GC runs since reset. */
    public TextBuffer getText() {
        return text;
    }

    public int getDrawCalls() {
        return drawCalls;
    }

    public int getTriangles() {
        return triangles;
    }

    public int getMaxDrawCalls() {
        return maxDrawCalls;
    }

    public int getMaxTriangles() {
        return maxTriangles;
    }

    /** GC runs since the first setGcCount() after construction or reset(). */
    public int getGcCount() {
        return gcBaseSet ? gcTotal - gcBase : 0;
    }

    /** Forgets the maximums and restarts the GC count from the current value. */
    public void reset() {
        maxDrawCalls = 0;
        maxTriangles = 0;
        gcBase = gcTotal;
        shownFps = -1;
    }
}
