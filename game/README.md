# The Maze

A first person 3D maze on the Skarph engine. Walk from the start to the gem; every maze has a random look
(day or night, brick or leaf walls, one of four seasons) and a random small size.

**Controls:** two zones, the screen split in half. Left half = walk: push the finger the way you want to go (up = forward,
sideways = strafe, down = back), relative to where you look; it keeps working if the finger slides into the right half.
Right half = look: drag the finger and the view follows it, sideways turns, up and down tilts (up to 80 degrees); it keeps
working in the left half too. Only two fingers are listened to (one per zone); with a single finger you do one thing at a time.
**Desktop (Windows, Linux, macOS):** the same game, with the keyboard and the mouse instead of the touch zones.
Walk: `W` `A` `S` `D`, or the arrows `up` / `down` for forward / back. Camera: the mouse (up = look up), or the arrows
`left` / `right` (120 degrees per second). There is no dot in the middle: the mouse is the aim. While you walk the cursor is
hidden and locked to the window; on the menu and on the result screen it is free and you click PLAY, NEXT, the sound
icon and the cross. `Esc` closes the game, `F11` or `Alt+Enter` toggles full screen. The keys are physical keys, so `WASD`
works on any keyboard layout.
**Looks:** walls are 2.6 high, bushes 1.7. Night sky is a little lighter; the dark around the flashlight is 85% opaque.
**Menu:** a fixed flight through one big maze, PLAY, a sound icon (top right). **Win:** the time, NEXT, a cross (back to the menu).

## Build

Same as the demos (see `demos/README.md`): an Android project with `minSdkVersion` 3, source folders `skarph-core/src`,
`skarph-android/src` and `game/src`, the files of `game/assets` in the app `assets/`, `game/res` as `res/`,
and `game/AndroidManifest.xml`.
App name "The Maze", package `com.skarph.the.maze` (the sources stay in the Java package `maze`, the activity is
referenced by its full name), version 1.0 = engine version, the build number is `versionCode`.

### Desktop jar

```
./build-desktop.sh          # -> build/desktop/the-maze.jar, run it with: java -jar build/desktop/the-maze.jar
```

The jar needs Java 8 or newer; LWJGL is downloaded on the first start (see `docs/MANUAL.md`, section 14). The desktop
start is `game/desktop/DesktopMaze.java` (the `Game` of the desktop layer); `DesktopPad.java` turns keys and mouse into the
numbers of the `Pad` interface that `MazeScreen` reads. `MazeScreen.setPad` is never called on Android.

## Files

```
src/maze/Maze.java        maze generator and solver (pure Java)
src/maze/Variant.java     the 16 looks: colors, light, fog
src/maze/MazeScreen.java  the whole game: menu flight, play, win
src/maze/Pad.java         the desktop controls as numbers (walk, turn, mouse look, cursor lock); pure Java
src/maze/GameActivity.java
desktop/DesktopMaze.java  the desktop start (window title and size, hands the keyboard and mouse to the game)
desktop/DesktopPad.java   keys and mouse of the desktop layer -> Pad
tools/gen_assets.py       makes every texture, icon and sound (numpy, Pillow)
tools/preview.py          CPU preview of a maze view (with tests/Preview.java)
tests/MazeGameTest.java   headless test of the touch game, run from the engine root (build.sh runs both tests):
  javac -cp <core classes> -d out tests/ContentTest.java game/tests/MazeGameTest.java
  java -cp <core classes>:out MazeGameTest
tests/MazeDesktopTest.java  headless test of keys, mouse look, locked and free cursor, a bot that solves mazes with W and the mouse
```
