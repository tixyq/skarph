// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.Context;

import skarph.core.Storage;

/** skarph.core.Storage backed by the app's private internal files (no permissions needed). */
public final class AndroidStorage implements Storage {
    private final Context context;

    public AndroidStorage(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    public byte[] load(String name) {
        InputStream in = null;
        try {
            in = context.openFileInput(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    public boolean save(String name, byte[] data) {
        FileOutputStream out = null;
        try {
            out = context.openFileOutput(name, Context.MODE_PRIVATE);
            out.write(data);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    public boolean delete(String name) {
        return context.deleteFile(name);
    }
}
