package maze;

/**
 * The desktop controls of the game (keyboard and mouse look), in plain numbers so that the game itself knows no key
 * and no window. On Android there is no Pad (MazeScreen.setPad is not called) and the two touch zones are used.
 */
public interface Pad {
    /** Sideways walk, -1 (left) .. 1 (right). */
    float getWalkX();

    /** Forward walk, -1 (back) .. 1 (forward). */
    float getWalkY();

    /** Turning with the keys, -1 (left) .. 1 (right); the game turns this many key steps per tick. */
    float getTurn();

    /** Mouse movement to the right since the last call, in screen units. */
    float takeLookDx();

    /** Mouse movement down since the last call, in screen units. */
    float takeLookDy();

    /** true: the cursor is hidden and locked and the mouse is the camera (during the walk). false: a free cursor to click (menu, result). */
    void setLookMode(boolean on);
}
