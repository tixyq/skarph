// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Fixed-capacity list of ints (no boxing, no allocation after construction). */
public final class IntList {
    private final int[] data;
    private int size;

    public IntList(int capacity) {
        data = new int[capacity];
    }

    /** @return false if the list is full. */
    public boolean add(int value) {
        if (size == data.length) {
            return false;
        }
        data[size++] = value;
        return true;
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        return data[index];
    }

    public void set(int index, int value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        data[index] = value;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return data.length;
    }

    public void clear() {
        size = 0;
    }

    public int indexOf(int value) {
        for (int i = 0; i < size; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public boolean contains(int value) {
        return indexOf(value) >= 0;
    }

    /** Removes the element and keeps the order (O(n)). */
    public void removeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        System.arraycopy(data, index + 1, data, index, size - index - 1);
        size--;
    }

    /** Removes the element by moving the last one into its place (O(1), order changes). */
    public void removeSwap(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        data[index] = data[--size];
    }
}
