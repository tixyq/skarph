// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Persistent private storage for small files (settings, saved games). */
public interface Storage {
    /** @return the file contents, or null if it does not exist. */
    byte[] load(String name);

    /** Writes (replaces) a file. @return false on failure. Names must not contain '/'. */
    boolean save(String name, byte[] data);

    boolean delete(String name);
}
