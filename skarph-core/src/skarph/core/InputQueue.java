// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Fixed-size ring buffer of input events. Thread-safe (UI thread pushes,
 * render thread polls). Zero allocation.
 * When full: MOVE events are dropped; DOWN/UP overwrite the newest event so
 * a "finger up" is never lost.
 */
public final class InputQueue {
    private final int capacity;
    private final int[] types;
    private final int[] pointers;
    private final float[] xs;
    private final float[] ys;
    private int head;
    private int count;

    public InputQueue(int capacity) {
        this.capacity = capacity;
        types = new int[capacity];
        pointers = new int[capacity];
        xs = new float[capacity];
        ys = new float[capacity];
    }

    public synchronized void push(int type, int pointer, float x, float y) {
        int i;
        if (count == capacity) {
            if (type == InputEvent.MOVE) {
                return;
            }
            i = (head + count - 1) % capacity;
        } else {
            i = (head + count) % capacity;
            count++;
        }
        types[i] = type;
        pointers[i] = pointer;
        xs[i] = x;
        ys[i] = y;
    }

    /** @return false if the queue is empty. */
    public synchronized boolean poll(InputEvent out) {
        if (count == 0) {
            return false;
        }
        out.type = types[head];
        out.pointer = pointers[head];
        out.x = xs[head];
        out.y = ys[head];
        head = (head + 1) % capacity;
        count--;
        return true;
    }

    public synchronized void clear() {
        head = 0;
        count = 0;
    }

    public synchronized int size() {
        return count;
    }
}
