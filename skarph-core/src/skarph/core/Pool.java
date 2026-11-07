// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Fixed-size object pool. All objects are created up front.
 * obtain() returns null when the pool is exhausted (it never allocates).
 * Do not free the same object twice.
 */
public final class Pool<T> {
    public interface Factory<E> {
        E create();
    }

    /** Optional: objects implementing this are reset() when returned to the pool. */
    public interface Poolable {
        void reset();
    }

    private final Object[] items;
    private final int capacity;
    private int count;

    public Pool(Factory<T> factory, int capacity) {
        this.capacity = capacity;
        items = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            items[i] = factory.create();
        }
        count = capacity;
    }

    @SuppressWarnings("unchecked")
    public T obtain() {
        if (count == 0) {
            return null;
        }
        return (T) items[--count];
    }

    public void free(T obj) {
        if (count == capacity) {
            return;
        }
        if (obj instanceof Poolable) {
            ((Poolable) obj).reset();
        }
        items[count++] = obj;
    }

    public int getFreeCount() {
        return count;
    }

    public int getCapacity() {
        return capacity;
    }
}
