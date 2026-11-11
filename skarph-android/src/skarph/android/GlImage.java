// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

/** A decoded image ready to be uploaded as a texture. */
public interface GlImage {
    int getWidth();

    int getHeight();

    /** Uploads the pixels into the texture that is currently bound to GL_TEXTURE_2D. */
    void upload();

    /** Frees the CPU memory of the image (call after upload). */
    void recycle();
}
