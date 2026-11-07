// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import android.os.Debug;

/** Source of the GC run count for Profiler.setGcCount(). Exists since Android 1.0. */
public final class AndroidGc {
    private AndroidGc() {
    }

    public static int count() {
        return Debug.getGlobalGcInvocationCount();
    }
}
