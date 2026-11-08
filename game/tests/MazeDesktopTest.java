import java.util.*;
import maze.*;
import skarph.core.*;
import skarph.desktop.DesktopInput;

/**
 * Headless test of the desktop controls of the maze game (plain Java): keys, mouse look, the locked cursor in the walk
 * and the free cursor in the menu and on the result, a bot that plays with keys and mouse only, allocation.
 * Runs together with MazeGameTest (it uses its fake renderer and sound), from the engine root.
 */
public class MazeDesktopTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static float wrap(float a) { while (a > 180f) a -= 360f; while (a < -180f) a += 360f; return a; }

    /** What the window does after every frame: applies the capture request. */
    static void frame(Engine e, MazeGameTest.R r, DesktopInput in) {
        MazeGameTest.frame(e, r);
        in.syncCapture();
    }

    static void tap(Engine e, MazeGameTest.R r, DesktopInput in, float px, float py) {
        e.input.push(InputEvent.DOWN, 0, px, py);
        e.input.push(InputEvent.UP, 0, px, py);
        frame(e, r, in);
    }

    static final class Game {
        final MazeGameTest.R r = new MazeGameTest.R();
        final DesktopInput in = new DesktopInput();
        final Engine e = new Engine();
        final MazeScreen s = new MazeScreen();
        float mx = 0f, my = 0f;                       // the mouse position the test moves around

        Game() {
            e.setAssets(new ContentTest.FileAssets("game/assets"));
            e.setAudio(new MazeGameTest.RecAudio());
            s.setPad(new DesktopPad(in));
            e.setScreen(s);
            for (int i = 0; i < 3; i++) frame();
        }

        void frame() { MazeDesktopTest.frame(e, r, in); }

        void frames(int n) { for (int i = 0; i < n; i++) frame(); }

        void play() { tap(e, r, in, 400f, 390f); in.cursorMoved(mx, my); }

        /** The mouse moves by (dx, dy) screen units, as the window reports it while the cursor is locked. */
        void mouse(float dx, float dy) {
            mx += dx;
            my += dy;
            in.cursorMoved(mx, my);
        }

        void key(int k, boolean down) { in.keyEvent(k, down); }
    }

    /** Solves the current maze with W and the mouse only. @return ticks used, -1 if stuck. */
    static int playBot(Game g, int limit) {
        Maze m = g.s.getMaze();
        g.key(DesktopInput.KEY_W, true);
        int ticks = 0;
        boolean wallsOk = true;
        while (g.s.getMode() == MazeScreen.PLAY && ticks < limit) {
            float px = g.s.getPlayerX(), pz = g.s.getPlayerZ();
            int best = 0;
            for (int i = 0; i < m.pathLen; i++) {
                float cx = (m.path[i] % m.w + 0.5f) * 2f, cz = (m.path[i] / m.w + 0.5f) * 2f;
                if (Math.hypot(cx - px, cz - pz) <= 2.5) best = i;
            }
            int t = Math.min(best + 1, m.pathLen - 1);
            float tx = (m.path[t] % m.w + 0.5f) * 2f, tz = (m.path[t] / m.w + 0.5f) * 2f;
            float want = (float) Math.toDegrees(Math.atan2(tx - px, -(tz - pz)));
            float diff = Math.max(-14f, Math.min(14f, wrap(want - g.s.getYaw())));
            g.mouse(diff / 0.15f, 0f);
            g.frame();
            ticks++;
            wallsOk &= !m.isWall((int) Math.floor(g.s.getPlayerX() / 2f), (int) Math.floor(g.s.getPlayerZ() / 2f));
        }
        g.key(DesktopInput.KEY_W, false);
        if (!wallsOk) check(false, "bot: the player stayed out of the walls");
        return g.s.getMode() == MazeScreen.WIN ? ticks : -1;
    }

    /** Draws one frame of a game on a day look (no flashlight) and returns how many 2D quads it drew; -1 if no day look came up. */
    static int quadsInDayFrame(boolean withPad) {
        for (int attempt = 0; attempt < 60; attempt++) {
            MazeGameTest.R r = new MazeGameTest.R();
            DesktopInput in = new DesktopInput();
            Engine e = new Engine();
            MazeScreen s = new MazeScreen();
            e.setAssets(new ContentTest.FileAssets("game/assets"));
            e.setAudio(new MazeGameTest.RecAudio());
            if (withPad) s.setPad(new DesktopPad(in));
            e.setScreen(s);
            for (int i = 0; i < 3; i++) MazeGameTest.frame(e, r);
            MazeGameTest.tap(e, r, 400f, 390f);
            if (Variant.isNight(s.getVariant())) continue;
            int before = r.quads;
            MazeGameTest.frame(e, r);
            return r.quads - before;
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        // ---- keys -> numbers
        DesktopInput k = new DesktopInput();
        DesktopPad pad = new DesktopPad(k);
        check(pad.getWalkX() == 0f && pad.getWalkY() == 0f && pad.getTurn() == 0f, "pad: no key, no movement");
        k.keyEvent(DesktopInput.KEY_W, true);
        check(pad.getWalkY() == 1f && pad.getWalkX() == 0f, "pad: W walks forward");
        k.keyEvent(DesktopInput.KEY_W, false);
        k.keyEvent(DesktopInput.KEY_UP, true);
        check(pad.getWalkY() == 1f && pad.getTurn() == 0f, "pad: the arrow up walks forward too");
        k.keyEvent(DesktopInput.KEY_S, true);
        check(pad.getWalkY() == 0f, "pad: forward and back at once cancel out");
        k.keyEvent(DesktopInput.KEY_UP, false);
        check(pad.getWalkY() == -1f, "pad: S walks back");
        k.keyEvent(DesktopInput.KEY_S, false);
        k.keyEvent(DesktopInput.KEY_DOWN, true);
        check(pad.getWalkY() == -1f, "pad: the arrow down walks back too");
        k.keyEvent(DesktopInput.KEY_DOWN, false);
        k.keyEvent(DesktopInput.KEY_D, true);
        check(pad.getWalkX() == 1f, "pad: D steps right");
        k.keyEvent(DesktopInput.KEY_D, false);
        k.keyEvent(DesktopInput.KEY_A, true);
        check(pad.getWalkX() == -1f, "pad: A steps left");
        k.keyEvent(DesktopInput.KEY_A, false);
        k.keyEvent(DesktopInput.KEY_RIGHT, true);
        check(pad.getTurn() == 1f && pad.getWalkX() == 0f && pad.getWalkY() == 0f, "pad: the arrow right turns the camera and does not walk");
        k.keyEvent(DesktopInput.KEY_RIGHT, false);
        k.keyEvent(DesktopInput.KEY_LEFT, true);
        check(pad.getTurn() == -1f && pad.getWalkX() == 0f, "pad: the arrow left turns the camera and does not strafe");
        k.keyEvent(DesktopInput.KEY_LEFT, false);

        // ---- the menu: free cursor, keys do nothing
        Game g = new Game();
        check(g.s.getMode() == MazeScreen.MENU && !g.in.isCursorCaptureWanted() && !g.in.isCaptureActive(),
                "menu: the cursor is free");
        g.key(DesktopInput.KEY_W, true);
        g.frames(30);
        check(g.s.getMode() == MazeScreen.MENU, "menu: a key does not start the game");
        g.key(DesktopInput.KEY_W, false);

        // ---- the walk: locked cursor, no touch zones
        g.play();
        check(g.s.getMode() == MazeScreen.PLAY, "PLAY: the button starts the game with the mouse click");
        check(g.in.isCursorCaptureWanted() && g.in.isCaptureActive(), "PLAY: the cursor is locked");
        float sx = g.s.getPlayerX(), sz = g.s.getPlayerZ();
        g.frames(30);
        check(g.s.getPlayerX() == sx && g.s.getPlayerZ() == sz && g.s.getStickX() == 0f && g.s.getStickY() == 0f,
                "PLAY: no key, no movement");
        float yaw0 = g.s.getYaw();
        // touch events mean nothing now: no stick, no look drag
        g.e.input.push(InputEvent.DOWN, 0, 200f, 300f);
        g.e.input.push(InputEvent.MOVE, 0, 200f, 200f);
        g.frames(10);
        g.e.input.push(InputEvent.UP, 0, 200f, 200f);
        g.e.input.push(InputEvent.DOWN, 0, 600f, 240f);
        g.e.input.push(InputEvent.MOVE, 0, 720f, 180f);
        g.frames(5);
        g.e.input.push(InputEvent.UP, 0, 720f, 180f);
        g.frame();
        check(g.s.getPlayerX() == sx && g.s.getPlayerZ() == sz && g.s.getYaw() == yaw0 && g.s.getPitch() == 0f,
                "PLAY: clicks and drags do nothing (the mouse is locked to the camera)");

        // W walks
        g.key(DesktopInput.KEY_W, true);
        g.frames(20);
        check(g.s.getPlayerX() != sx || g.s.getPlayerZ() != sz, "PLAY: W walks");
        check(g.s.getStickY() == -1f && g.s.getStickX() == 0f, "PLAY: W is a full stick forward (" + g.s.getStickX() + ", " + g.s.getStickY() + ")");
        g.key(DesktopInput.KEY_W, false);
        g.key(DesktopInput.KEY_UP, true);
        g.frame();
        check(g.s.getStickY() == -1f, "PLAY: the arrow up is the same");
        g.key(DesktopInput.KEY_UP, false);
        g.key(DesktopInput.KEY_S, true);
        g.frame();
        check(g.s.getStickY() == 1f && g.s.getStickX() == 0f, "PLAY: S walks back");
        g.key(DesktopInput.KEY_S, false);
        g.key(DesktopInput.KEY_DOWN, true);
        g.frame();
        check(g.s.getStickY() == 1f, "PLAY: the arrow down is the same");
        g.key(DesktopInput.KEY_DOWN, false);
        g.key(DesktopInput.KEY_A, true);
        g.frame();
        check(g.s.getStickX() == -1f && g.s.getStickY() == 0f, "PLAY: A steps left");
        g.key(DesktopInput.KEY_A, false);
        g.key(DesktopInput.KEY_D, true);
        g.frame();
        check(g.s.getStickX() == 1f, "PLAY: D steps right");
        g.key(DesktopInput.KEY_W, true);
        g.frame();
        double len = Math.hypot(g.s.getStickX(), g.s.getStickY());
        check(Math.abs(len - 1.0) < 0.001 && g.s.getStickX() > 0f && g.s.getStickY() < 0f,
                "PLAY: W + D walks diagonally, not faster (stick length " + len + ")");
        g.key(DesktopInput.KEY_W, false);
        g.key(DesktopInput.KEY_D, false);
        g.frame();
        check(g.s.getStickX() == 0f && g.s.getStickY() == 0f, "PLAY: letting go of the keys stops the walk");

        // arrows left and right turn the camera, 2 degrees per tick, and do not walk
        float px1 = g.s.getPlayerX(), pz1 = g.s.getPlayerZ(), yaw1 = g.s.getYaw();
        g.key(DesktopInput.KEY_RIGHT, true);
        g.frames(60);
        g.key(DesktopInput.KEY_RIGHT, false);
        check(Math.abs(wrap(g.s.getYaw() - yaw1) - 120f) < 0.5f, "PLAY: the arrow right turns 120 degrees in a second (" + wrap(g.s.getYaw() - yaw1) + ")");
        g.key(DesktopInput.KEY_LEFT, true);
        g.frames(30);
        g.key(DesktopInput.KEY_LEFT, false);
        check(Math.abs(wrap(g.s.getYaw() - yaw1) - 60f) < 0.5f, "PLAY: the arrow left turns back");
        check(g.s.getPlayerX() == px1 && g.s.getPlayerZ() == pz1, "PLAY: the turning arrows do not walk");

        // mouse look: 0.15 degrees per unit, up = look up, stops at 80
        float yaw2 = g.s.getYaw();
        g.mouse(100f, 0f);
        g.frame();
        check(Math.abs(wrap(g.s.getYaw() - yaw2) - 15f) < 0.01f, "PLAY: the mouse 100 units to the right turns right by 15 degrees");
        g.mouse(-200f, 0f);
        g.frame();
        check(Math.abs(wrap(g.s.getYaw() - yaw2) + 15f) < 0.01f, "PLAY: and to the left turns back");
        g.mouse(0f, -100f);
        g.frame();
        check(Math.abs(g.s.getPitch() - 15f) < 0.01f, "PLAY: the mouse moved up looks up (" + g.s.getPitch() + ")");
        g.mouse(0f, -100000f);
        g.frame();
        check(g.s.getPitch() == 80f, "PLAY: looking up stops at 80 degrees");
        g.mouse(0f, 100000f);
        g.frame();
        check(g.s.getPitch() == -80f, "PLAY: looking down stops at -80 degrees");
        g.mouse(123456f, 0f);
        g.mouse(-70000f, 0f);
        g.frame();
        check(g.s.getYaw() >= 0f && g.s.getYaw() < 360f, "PLAY: a huge swing keeps the heading in 0..360 (" + g.s.getYaw() + ")");
        // mouse and keys together: look and walk at once
        float yaw3 = g.s.getYaw();
        g.key(DesktopInput.KEY_W, true);
        g.mouse(40f, 0f);
        g.frame();
        check(g.s.getStickY() == -1f && wrap(g.s.getYaw() - yaw3) > 5.9f, "PLAY: walking and looking work together");
        g.key(DesktopInput.KEY_W, false);

        // focus lost: no stuck key
        g.key(DesktopInput.KEY_W, true);
        g.in.focusChanged(false);
        g.frame();
        check(g.s.getStickY() == 0f, "PLAY: a key held while the window loses the focus does not stay pressed");

        // ---- a bot that plays with W and the mouse only; the cursor is free again on every result
        int ok = 0, fails = 0, freeOnWin = 0, lockedOnPlay = 0;
        boolean freeAfterNext = false;
        for (int launch = 0; launch < 3; launch++) {
            Game b = new Game();
            b.play();
            for (int games = 0; games < 3; games++) {
                if (b.in.isCaptureActive()) lockedOnPlay++;
                int t = playBot(b, 6000);
                if (t < 0) { fails++; break; }
                ok++;
                if (b.s.getMode() == MazeScreen.WIN && !b.in.isCursorCaptureWanted() && !b.in.isCaptureActive()) freeOnWin++;
                // the result screen: the mouse clicks NEXT
                tap(b.e, b.r, b.in, 400f, 312f);
                if (b.s.getMode() != MazeScreen.PLAY) { fails++; break; }
                b.in.cursorMoved(b.mx, b.my);
                if (!b.in.isCaptureActive()) fails++;
            }
            // from the result to the menu with the cross, the cursor stays free there
            if (b.s.getMode() == MazeScreen.PLAY) {
                playBot(b, 6000);
                tap(b.e, b.r, b.in, (800f / 480f * 320f - 28f) * 1.5f, 28f * 1.5f);
                freeAfterNext = b.s.getMode() == MazeScreen.MENU && !b.in.isCaptureActive();
            }
        }
        check(fails == 0 && ok >= 9, "bot: " + ok + " mazes solved with W and the mouse only, " + fails + " failures");
        check(freeOnWin == ok, "WIN: the cursor is free on every result (" + freeOnWin + " of " + ok + ")");
        check(lockedOnPlay >= 9, "PLAY: the cursor is locked in every walk (" + lockedOnPlay + ")");
        check(freeAfterNext, "WIN: the cross leads to the menu and the cursor stays free");

        // ---- the center dot is only for touch
        int withPad = quadsInDayFrame(true), without = quadsInDayFrame(false);
        check(withPad == 0, "PLAY: no center dot with the mouse (" + withPad + " quads on a day look)");
        check(without >= 2, "PLAY: the center dot is still there for touch (" + without + " quads)");

        // ---- no garbage in the walk
        Game m = new Game();
        m.play();
        m.key(DesktopInput.KEY_W, true);
        for (int i = 0; i < 100; i++) { m.mouse((i % 2) * 6f - 3f, (i % 3) - 1f); m.frame(); }
        long a0 = MazeGameTest.allocated();
        int frames = 0;
        for (; frames < 1000 && m.s.getMode() == MazeScreen.PLAY; frames++) {
            m.mouse((frames % 2) * 6f - 3f, (frames % 3) - 1f);
            m.frame();
        }
        long alloc = MazeGameTest.allocated() - a0;
        check(frames > 300, "memory: the walk test ran " + frames + " frames");
        check(alloc < 4096, "memory: 1000 desktop game frames allocate " + alloc + " bytes");

        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
