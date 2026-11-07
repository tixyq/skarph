// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Fixed-capacity hash map int -> int (open addressing, no boxing, no
 * allocation after construction). Capacity is the maximum number of entries.
 */
public final class IntIntMap {
    private final int[] keys;
    private final int[] values;
    private final boolean[] used;
    private final int mask;
    private final int maxSize;
    private int size;

    public IntIntMap(int capacity) {
        int n = 4;
        while (n < capacity * 2) {
            n <<= 1;
        }
        keys = new int[n];
        values = new int[n];
        used = new boolean[n];
        mask = n - 1;
        maxSize = capacity;
    }

    private int slot(int key) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }

    /** @return false if the map is full and the key is new. */
    public boolean put(int key, int value) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value;
                return true;
            }
            i = (i + 1) & mask;
        }
        if (size >= maxSize) {
            return false;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
        return true;
    }

    public int get(int key, int defaultValue) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
        return defaultValue;
    }

    public boolean containsKey(int key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    public boolean remove(int key) {
        int i = slot(key);
        while (used[i] && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (!used[i]) {
            return false;
        }
        // backward shift deletion keeps probe chains intact
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (!used[j]) {
                break;
            }
            int k = slot(keys[j]);
            boolean inRange = (i <= j) ? (i < k && k <= j) : (i < k || k <= j);
            if (!inRange) {
                keys[i] = keys[j];
                values[i] = values[j];
                i = j;
            }
        }
        used[i] = false;
        size--;
        return true;
    }

    public int size() {
        return size;
    }

    public void clear() {
        for (int i = 0; i < used.length; i++) {
            used[i] = false;
        }
        size = 0;
    }
}
