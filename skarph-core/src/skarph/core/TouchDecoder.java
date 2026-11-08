// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Turns the raw data of a touch event (as delivered by the platform, including multi-touch) into
 * DOWN / MOVE / UP events with pointer ids in an InputQueue. Pure logic, no platform classes, so
 * it is unit tested. The platform layer only extracts the arrays from the native event.
 * Zero allocation.
 */
public final class TouchDecoder {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    private TouchDecoder() {
    }

    /**
     * @param action MotionEvent action without the pointer index bits
     * @param actionIndex index (into the arrays) of the pointer that went down or up
     * @param count number of valid entries in ids/xs/ys
     */
    public static void decode(int action, int actionIndex, int count,
                              int[] ids, float[] xs, float[] ys, InputQueue queue) {
        switch (action) {
            case ACTION_DOWN:
                if (count > 0) {
                    queue.push(InputEvent.DOWN, ids[0], xs[0], ys[0]);
                }
                break;
            case ACTION_POINTER_DOWN:
                if (actionIndex >= 0 && actionIndex < count) {
                    queue.push(InputEvent.DOWN, ids[actionIndex], xs[actionIndex], ys[actionIndex]);
                }
                break;
            case ACTION_MOVE:
                for (int i = 0; i < count; i++) {
                    queue.push(InputEvent.MOVE, ids[i], xs[i], ys[i]);
                }
                break;
            case ACTION_POINTER_UP:
                if (actionIndex >= 0 && actionIndex < count) {
                    queue.push(InputEvent.UP, ids[actionIndex], xs[actionIndex], ys[actionIndex]);
                }
                break;
            case ACTION_UP:
            case ACTION_CANCEL:
                for (int i = 0; i < count; i++) {
                    queue.push(InputEvent.UP, ids[i], xs[i], ys[i]);
                }
                break;
            default:
                break;
        }
    }
}
