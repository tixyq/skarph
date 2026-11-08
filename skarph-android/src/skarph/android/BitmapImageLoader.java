// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.io.IOException;
import java.io.InputStream;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLUtils;
import android.util.Log;

/** GlImageLoader for Android: decodes PNGs from assets with BitmapFactory, uploads with GLUtils. */
public final class BitmapImageLoader implements GlImageLoader {
    private static final String TAG = "skarph";
    private final AssetManager assets;

    public BitmapImageLoader(AssetManager assets) {
        this.assets = assets;
    }

    public GlImage load(String assetPath) {
        InputStream in = null;
        Bitmap bmp = null;
        try {
            in = assets.open(assetPath);
            BitmapFactory.Options opts = BitmapOptionsCompat.exactPixels(); // never rescale (API 3 safe)
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Log.e(TAG, "cannot open asset " + assetPath);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        if (bmp == null) {
            return null;
        }
        final Bitmap bitmap = bmp;
        return new GlImage() {
            public int getWidth() {
                return bitmap.getWidth();
            }

            public int getHeight() {
                return bitmap.getHeight();
            }

            public void upload() {
                GLUtils.texImage2D(Gl2.GL_TEXTURE_2D, 0, bitmap, 0);
            }

            public void recycle() {
                bitmap.recycle();
            }
        };
    }
}
