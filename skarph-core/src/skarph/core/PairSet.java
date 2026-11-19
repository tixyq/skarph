// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A fixed capacity set of body pairs (index and uid of each body), open addressing, no allocation after construction.
 * Used by PhysicsWorld to tell new contacts from continuing ones and to report contacts that ended.
 */
final class PairSet {
    final int[] a;
    final int[] ua;
    final int[] b;
    final int[] ub;
    int size;
    private final int[] table;      // entry index + 1, 0 = empty
    private final int mask;

    PairSet(int capacity) {
        int n = 16;
        while (n < capacity * 2) {
            n <<= 1;
        }
        table = new int[n];
        mask = n - 1;
        a = new int[capacity];
        ua = new int[capacity];
        b = new int[capacity];
        ub = new int[capacity];
    }

    void clear() {
        if (size > 0) {
            for (int i = 0; i < table.length; i++) {
                table[i] = 0;
            }
            size = 0;
        }
    }

    private int slot(int ia, int ib) {
        return (ia * 73856093 ^ ib * 19349663) & mask;
    }

    /** Looks up the pair (index a < index b). @return its entry or -1 */
    int find(int ia, int uia, int ib, int uib) {
        int h = slot(ia, ib);
        while (true) {
            int e = table[h];
            if (e == 0) {
                return -1;
            }
            e--;
            if (a[e] == ia && b[e] == ib && ua[e] == uia && ub[e] == uib) {
                return e;
            }
            h = (h + 1) & mask;
        }
    }

    /** Adds the pair if it is not there. @return false if the set is full */
    boolean add(int ia, int uia, int ib, int uib) {
        if (size >= a.length) {
            return false;
        }
        int h = slot(ia, ib);
        while (true) {
            int e = table[h];
            if (e == 0) {
                break;
            }
            e--;
            if (a[e] == ia && b[e] == ib && ua[e] == uia && ub[e] == uib) {
                return true;
            }
            h = (h + 1) & mask;
        }
        a[size] = ia;
        ua[size] = uia;
        b[size] = ib;
        ub[size] = uib;
        size++;
        table[h] = size;
        return true;
    }
}
