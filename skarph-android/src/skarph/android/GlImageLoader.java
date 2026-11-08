// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

/** Decodes image files (PNG) from the app assets. */
public interface GlImageLoader {
    /** @return the image, or null if the file is missing or cannot be decoded. */
    GlImage load(String assetPath);
}
