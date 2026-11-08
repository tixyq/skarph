// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import android.util.Log;

/** GlLog that writes to logcat with the tag "skarph". */
public final class AndroidLog implements GlLog {
    private static final String TAG = "skarph";

    public void info(String message) {
        Log.i(TAG, message);
    }

    public void warn(String message) {
        Log.w(TAG, message);
    }

    public void error(String message) {
        Log.e(TAG, message);
    }
}
