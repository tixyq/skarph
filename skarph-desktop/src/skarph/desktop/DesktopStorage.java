// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import skarph.core.Storage;

/** skarph.core.Storage in a folder of the application data of the system. A save is written to a temporary file first. */
public final class DesktopStorage implements Storage {
    private final File dir;

    public DesktopStorage(File dir) {
        this.dir = dir;
    }

    private File file(String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.equals(".") || name.equals("..") || name.indexOf(':') >= 0) {
            return null;
        }
        return new File(dir, name);
    }

    public byte[] load(String name) {
        File f = file(name);
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    public boolean save(String name, byte[] data) {
        File f = file(name);
        if (f == null || (!dir.isDirectory() && !dir.mkdirs())) {
            return false;
        }
        File tmp = new File(dir, name + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (f.exists() && !f.delete()) {
                return false;
            }
            return tmp.renameTo(f);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    public boolean delete(String name) {
        File f = file(name);
        return f != null && f.delete();
    }
}
