// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Tracks which fingers are down and where (up to MAX_POINTERS pointer ids), and measures
 * pinch gestures. Feed it every InputEvent you read from engine.input.
 * Zero allocation.
 */
public final class TouchState {
    public static final int MAX_POINTERS = 16;

    private final boolean[] down = new boolean[MAX_POINTERS];
    private final float[] x = new float[MAX_POINTERS];
    private final float[] y = new float[MAX_POINTERS];
    private final float[] startX = new float[MAX_POINTERS];
    private final float[] startY = new float[MAX_POINTERS];
    private int downCount;
    private float lastPinch = -1f;
    private float pinchAccum;

    public void feed(InputEvent e) {
        int id = e.pointer;
        if (id < 0 || id >= MAX_POINTERS) {
            return;
        }
        switch (e.type) {
            case InputEvent.DOWN:
                if (!down[id]) {
                    down[id] = true;
                    downCount++;
                }
                startX[id] = e.x;
                startY[id] = e.y;
                x[id] = e.x;
                y[id] = e.y;
                lastPinch = -1f;
                break;
            case InputEvent.MOVE:
                if (down[id]) {
                    x[id] = e.x;
                    y[id] = e.y;
                    updatePinch();
                }
                break;
            case InputEvent.UP:
                if (down[id]) {
                    down[id] = false;
                    downCount--;
                    x[id] = e.x;
                    y[id] = e.y;
                }
                lastPinch = -1f;
                break;
            default:
                break;
        }
    }

    public int getDownCount() {
        return downCount;
    }

    public boolean isDown(int id) {
        return id >= 0 && id < MAX_POINTERS && down[id];
    }

    public float getX(int id) {
        return x[id];
    }

    public float getY(int id) {
        return y[id];
    }

    public float getStartX(int id) {
        return startX[id];
    }

    public float getStartY(int id) {
        return startY[id];
    }

    /** Distance moved since the finger went down. */
    public float getDragDistance(int id) {
        float dx = x[id] - startX[id];
        float dy = y[id] - startY[id];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** @return the id of the n-th (0 based) finger that is down, or -1. Ids ascend. */
    public int getPointerId(int n) {
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (down[i]) {
                if (n == 0) {
                    return i;
                }
                n--;
            }
        }
        return -1;
    }

    public void reset() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            down[i] = false;
        }
        downCount = 0;
        lastPinch = -1f;
        pinchAccum = 0f;
    }

    private void updatePinch() {
        if (downCount != 2) {
            lastPinch = -1f;
            return;
        }
        int a = getPointerId(0);
        int b = getPointerId(1);
        float dx = x[a] - x[b];
        float dy = y[a] - y[b];
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (lastPinch >= 0f) {
            pinchAccum += d - lastPinch;
        }
        lastPinch = d;
    }

    /** Current distance between the first two fingers, or -1 unless exactly two fingers are down. */
    public float getPinchDistance() {
        if (downCount != 2) {
            return -1f;
        }
        int a = getPointerId(0);
        int b = getPointerId(1);
        float dx = x[a] - x[b];
        float dy = y[a] - y[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * How much the two-finger distance changed since the last call (positive = fingers moved
     * apart, in pixels). Returns 0 when no pinch is going on. Resets the counter.
     */
    public float consumePinch() {
        float p = pinchAccum;
        pinchAccum = 0f;
        return p;
    }
}
