// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** Decodes PNG (and every other format of ImageIO) into RGBA bytes, top row first, not premultiplied. */
public final class ImageDecoder {
    private ImageDecoder() {
    }

    public static final class Image {
        public final int width;
        public final int height;
        /** width * height * 4 bytes in a direct buffer, position 0. */
        public final ByteBuffer rgba;

        Image(int width, int height, ByteBuffer rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    /** @return the image, or null if the data cannot be decoded */
    public static Image decode(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            }
            ((Buffer) out).position(0);
            return new Image(w, h, out);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
