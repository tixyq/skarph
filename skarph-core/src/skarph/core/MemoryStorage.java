// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

import java.util.HashMap;

/** Storage kept in memory only: the default (and handy for tests). Nothing survives a restart. */
public final class MemoryStorage implements Storage {
    private final HashMap<String, byte[]> files = new HashMap<String, byte[]>();

    public byte[] load(String name) {
        byte[] d = files.get(name);
        if (d == null) {
            return null;
        }
        byte[] copy = new byte[d.length];
        System.arraycopy(d, 0, copy, 0, d.length);
        return copy;
    }

    public boolean save(String name, byte[] data) {
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        files.put(name, copy);
        return true;
    }

    public boolean delete(String name) {
        return files.remove(name) != null;
    }
}
