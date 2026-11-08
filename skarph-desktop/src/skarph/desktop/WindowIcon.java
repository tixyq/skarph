// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The icon of every window the desktop layer opens: the project's icon.png. The build copies that one file into the jar as
 * {@value #RESOURCE}; here it is decoded and scaled to the sizes window systems ask for. No icon in the jar means no
 * icon, never an error.
 */
public final class WindowIcon {
    /** Where the build puts the icon in the jar (a copy of icon.png from the project root). */
    public static final String RESOURCE = "/skarph/desktop/icon.png";
    /** Sizes handed to the window system: it picks the one that fits best (title bar, task bar, Alt+Tab). */
    public static final int[] SIZES = {256, 64, 32, 16};

    private WindowIcon() {
    }

    /** @return the icon in all SIZES, or an empty list if the jar has none or it cannot be decoded */
    public static List<BufferedImage> load() {
        byte[] data = readResource();
        return data == null ? new ArrayList<BufferedImage>() : scaled(data);
    }

    /** Decodes PNG bytes and scales them to SIZES (square image assumed); an empty list when the bytes are not an image. */
    public static List<BufferedImage> scaled(byte[] png) {
        List<BufferedImage> out = new ArrayList<BufferedImage>();
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) {
                return out;
            }
            for (int size : SIZES) {
                out.add(scale(src, size));
            }
        } catch (IOException e) {
            out.clear();
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** RGBA bytes of an image, top row first, in a direct buffer at position 0 (what GLFW wants). */
    public static ByteBuffer rgba(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int p : argb) {
            out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        ((Buffer) out).position(0);
        return out;
    }

    /** Sets the Swing window's icons (all sizes). Does nothing without an icon. */
    public static void apply(java.awt.Window window) {
        List<BufferedImage> icons = load();
        if (!icons.isEmpty()) {
            window.setIconImages(new ArrayList<java.awt.Image>(icons));
        }
    }

    /** The dock or task bar icon of the whole program, where Java can set it (Java 9+: macOS dock, some Linux desktops). */
    public static void applyTaskbar() {
        try {
            List<BufferedImage> icons = load();
            if (icons.isEmpty()) {
                return;
            }
            Class<?> taskbar = Class.forName("java.awt.Taskbar");
            Object tb = taskbar.getMethod("getTaskbar").invoke(null);
            taskbar.getMethod("setIconImage", java.awt.Image.class).invoke(tb, icons.get(0));
        } catch (Throwable ignored) {
            // Java 8, headless, or not supported by this desktop: the window icon is still set
        }
    }

    /** Big reductions (512 to 16) look rough in one step: halve while the result stays at least `size`, then the last step. */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= size) {
            cur = resize(cur, cur.getWidth() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (cur.getWidth() == size && cur.getHeight() == size && cur.getType() == BufferedImage.TYPE_INT_ARGB) {
            return cur;
        }
        return resize(cur, size, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage resize(BufferedImage src, int size, Object interpolation) {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] readResource() {
        InputStream in = WindowIcon.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
