// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Platform independent access to the application's data files. */
public interface Assets {
    /** Reads a whole file. @return the bytes, or null if the file does not exist or cannot be read. */
    byte[] readBytes(String path);
}
