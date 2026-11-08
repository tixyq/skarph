// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import skarph.core.Assets;

/**
 * skarph.core.Assets for the desktop. A file is looked for in this order: the folder given by -Dskarph.assets, the
 * folder "assets" of the program jar (the usual place: the assets are inside the one portable jar), a folder "assets" in
 * the current directory (handy while developing).
 */
public final class DesktopAssets implements Assets {
    private final ClassLoader loader;
    private final File overrideDir;
    private final File localDir;

    public DesktopAssets(ClassLoader loader, File overrideDir, File localDir) {
        this.loader = loader;
        this.overrideDir = overrideDir;
        this.localDir = localDir;
    }

    public byte[] readBytes(String path) {
        String p = clean(path);
        if (p == null) {
            return null;
        }
        try {
            if (overrideDir != null) {
                byte[] b = readFile(new File(overrideDir, p));
                if (b != null) {
                    return b;
                }
            }
            InputStream in = loader.getResourceAsStream("assets/" + p);
            if (in != null) {
                return readAll(in);
            }
            if (localDir != null) {
                return readFile(new File(localDir, p));
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Slashes only, no "..", no leading slash: an asset path can never leave the assets folder. */
    static String clean(String path) {
        if (path == null || path.length() == 0 || path.indexOf('\\') >= 0 || path.startsWith("/") || path.indexOf(':') >= 0) {
            return null;
        }
        for (String part : path.split("/", -1)) {
            if (part.length() == 0 || part.equals(".") || part.equals("..")) {
                return null;
            }
        }
        return path;
    }

    private static byte[] readFile(File f) throws IOException {
        if (!f.isFile()) {
            return null;
        }
        return readAll(new FileInputStream(f));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
