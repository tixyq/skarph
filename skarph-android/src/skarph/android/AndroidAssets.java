// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.res.AssetManager;

import skarph.core.Assets;

/** skarph.core.Assets backed by the Android assets folder. */
public final class AndroidAssets implements Assets {
    private final AssetManager manager;

    public AndroidAssets(AssetManager manager) {
        this.manager = manager;
    }

    public byte[] readBytes(String path) {
        InputStream in = null;
        try {
            in = manager.open(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
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
}
