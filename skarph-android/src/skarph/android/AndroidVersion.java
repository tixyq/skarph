// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import android.os.Build;

/**
 * Android version without Build.VERSION.SDK_INT (that field exists only since API 4 / Android 1.6).
 * Build.VERSION.SDK is a String that exists since API 1; it is parsed once.
 */
public final class AndroidVersion {
    /** The API level of the running device (3 for Android 1.5). Never below 1. */
    public static final int SDK = parse(Build.VERSION.SDK);

    private AndroidVersion() {
    }

    private static int parse(String s) {
        int v = 0;
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') {
                    break;
                }
                v = v * 10 + (c - '0');
            }
        }
        return v > 0 ? v : 1;
    }
}
