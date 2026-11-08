// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import android.view.MotionEvent;

import skarph.core.InputQueue;
import skarph.core.TouchDecoder;

/**
 * Multi-touch input. This class uses MotionEvent methods that exist only on Android 2.0 (API 5)
 * and newer, so it is isolated in its own class and EngineView only touches it when
 * AndroidVersion.SDK >= 5. On older versions it is never loaded.
 * (It is the only class compiled against a newer android.jar, see build.sh.)
 */
final class MultiTouchHandler {
    private static final int MAX = 16;
    private static final int[] IDS = new int[MAX];
    private static final float[] XS = new float[MAX];
    private static final float[] YS = new float[MAX];

    private MultiTouchHandler() {
    }

    /** Only called from the GUI thread, so the shared arrays are safe. */
    static void handle(MotionEvent e, InputQueue queue) {
        int action = e.getAction();
        int masked = action & 0xFF;
        int index = (action & 0xFF00) >> 8;
        int count = e.getPointerCount();
        if (count > MAX) {
            count = MAX;
        }
        for (int i = 0; i < count; i++) {
            IDS[i] = e.getPointerId(i);
            XS[i] = e.getX(i);
            YS[i] = e.getY(i);
        }
        TouchDecoder.decode(masked, index, count, IDS, XS, YS, queue);
    }
}
