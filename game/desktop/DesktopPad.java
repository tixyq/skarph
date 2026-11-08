package maze;

import skarph.desktop.DesktopInput;

/**
 * Keys and mouse of the desktop as a Pad. Walk: W and S, or the arrows up and down, forward and back; A and D step
 * sideways. Camera: the mouse, or the arrows left and right. Two opposite keys cancel each other.
 */
public final class DesktopPad implements Pad {
    private final DesktopInput in;

    public DesktopPad(DesktopInput in) {
        this.in = in;
    }

    private static float axis(boolean plus, boolean minus) {
        return (plus ? 1f : 0f) - (minus ? 1f : 0f);
    }

    public float getWalkX() {
        return axis(in.isKeyDown(DesktopInput.KEY_D), in.isKeyDown(DesktopInput.KEY_A));
    }

    public float getWalkY() {
        return axis(in.isKeyDown(DesktopInput.KEY_W) || in.isKeyDown(DesktopInput.KEY_UP),
                in.isKeyDown(DesktopInput.KEY_S) || in.isKeyDown(DesktopInput.KEY_DOWN));
    }

    public float getTurn() {
        return axis(in.isKeyDown(DesktopInput.KEY_RIGHT), in.isKeyDown(DesktopInput.KEY_LEFT));
    }

    public float takeLookDx() {
        return in.takeMouseDx();
    }

    public float takeLookDy() {
        return in.takeMouseDy();
    }

    public void setLookMode(boolean on) {
        in.setCursorCaptured(on);
    }
}
