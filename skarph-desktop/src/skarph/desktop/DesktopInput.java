// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

/**
 * Keyboard and mouse-look state for games that want more than "the mouse is one finger". Plain Java: the window
 * (Host) fills it from GLFW, the game reads it on the render thread (GLFW callbacks run inside glfwPollEvents on the
 * same thread, so no locking is needed). A game gets it through {@link InputAware}.
 *
 * <p>Keys: {@link #isKeyDown}. The codes are the GLFW key codes, which name PHYSICAL keys by their place on a US
 * keyboard, so W is the same key on a Russian or French layout.
 *
 * <p>Mouse look: the game asks for a captured cursor with {@link #setCursorCaptured}. The window then hides and locks
 * the cursor and collects its movement, in screen units, which the game takes with {@link #takeMouseDx} and
 * {@link #takeMouseDy}. While the cursor is captured the left button gives no touch events (a locked cursor has no
 * position); with a free cursor everything works as before: the mouse is pointer 0.
 */
public final class DesktopInput {
    public static final int KEY_A = 65, KEY_D = 68, KEY_S = 83, KEY_W = 87;
    public static final int KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265;
    /** Key codes at or above this value are not tracked (GLFW's last key code is 348). */
    public static final int MAX_KEY = 349;

    private final boolean[] down = new boolean[MAX_KEY];
    private float mouseDx;
    private float mouseDy;
    private boolean captureWanted;
    private boolean captureActive;
    private boolean haveLast;
    private double lastX;
    private double lastY;

    // ------------------------------------------------------------------ for the game

    public boolean isKeyDown(int key) {
        return key >= 0 && key < MAX_KEY && down[key];
    }

    /** Asks for the cursor to be hidden and locked (true) or shown and free (false). Applied by the window after the frame. */
    public void setCursorCaptured(boolean on) {
        captureWanted = on;
    }

    public boolean isCursorCaptureWanted() {
        return captureWanted;
    }

    /** Horizontal mouse movement since the last call, in screen units, right = positive. Only moves while the cursor is captured. */
    public float takeMouseDx() {
        float v = mouseDx;
        mouseDx = 0f;
        return v;
    }

    /** Vertical mouse movement since the last call, in screen units, down = positive (as the screen). */
    public float takeMouseDy() {
        float v = mouseDy;
        mouseDy = 0f;
        return v;
    }

    // ------------------------------------------------------------------ for the window

    public void keyEvent(int key, boolean pressed) {
        if (key >= 0 && key < MAX_KEY) {
            down[key] = pressed;
        }
    }

    /** The cursor moved to (x, y) in screen units. While captured, the movement since the last call adds to the mouse delta. */
    public void cursorMoved(double x, double y) {
        if (captureActive) {
            if (haveLast) {
                mouseDx += (float) (x - lastX);
                mouseDy += (float) (y - lastY);
            }
            lastX = x;
            lastY = y;
            haveLast = true;
        }
    }

    /**
     * The window calls this once per frame. @return true when the capture state changed and the window must switch the
     * cursor mode now (then {@link #isCaptureActive} says to which). A switch drops the first movement: the cursor
     * position jumps when the mode changes and must not turn the view.
     */
    public boolean syncCapture() {
        if (captureWanted == captureActive) {
            return false;
        }
        captureActive = captureWanted;
        haveLast = false;
        mouseDx = 0f;
        mouseDy = 0f;
        return true;
    }

    public boolean isCaptureActive() {
        return captureActive;
    }

    /** The window lost or got the focus: no key stays pressed (the release goes to another window) and the next movement is not a jump. */
    public void focusChanged(boolean focused) {
        haveLast = false;
        if (!focused) {
            for (int i = 0; i < MAX_KEY; i++) {
                down[i] = false;
            }
        }
    }
}
