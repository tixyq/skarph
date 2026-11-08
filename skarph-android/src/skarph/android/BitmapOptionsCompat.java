// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.lang.reflect.Field;

import android.graphics.BitmapFactory;

/**
 * Switches off automatic bitmap scaling. BitmapFactory.Options.inScaled exists only since API 4;
 * on Android 1.5 there is no density scaling at all, so there is nothing to switch off.
 * The field is looked up once by reflection (not in a hot path: only at texture load).
 */
final class BitmapOptionsCompat {
    private static final Field IN_SCALED = find();

    private BitmapOptionsCompat() {
    }

    private static Field find() {
        try {
            return BitmapFactory.Options.class.getField("inScaled");
        } catch (Exception e) {
            return null; // API 3: no scaling to disable
        }
    }

    /** Exact pixels, no rescale, no extra memory. Safe on every API level. */
    static BitmapFactory.Options exactPixels() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        if (IN_SCALED != null) {
            try {
                IN_SCALED.setBoolean(opts, false);
            } catch (Exception ignored) {
                // keep the default
            }
        }
        return opts;
    }
}
