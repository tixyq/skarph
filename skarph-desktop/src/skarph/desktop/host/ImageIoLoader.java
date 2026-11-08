// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop.host;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;

import skarph.android.GlImage;
import skarph.android.GlImageLoader;
import skarph.core.Assets;
import skarph.desktop.ImageDecoder;

/** GlImageLoader: reads the file through Assets, decodes it with ImageIO, uploads RGBA with glTexImage2D. */
final class ImageIoLoader implements GlImageLoader {
    private final Assets assets;

    ImageIoLoader(Assets assets) {
        this.assets = assets;
    }

    public GlImage load(String assetPath) {
        byte[] data = assets.readBytes(assetPath);
        if (data == null) {
            return null;
        }
        final ImageDecoder.Image decoded = ImageDecoder.decode(data);
        if (decoded == null) {
            return null;
        }
        return new GlImage() {
            private ByteBuffer pixels = decoded.rgba;

            public int getWidth() {
                return decoded.width;
            }

            public int getHeight() {
                return decoded.height;
            }

            public void upload() {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, decoded.width, decoded.height, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            }

            public void recycle() {
                pixels = null;
            }
        };
    }
}
