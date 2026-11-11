// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

/** Headless tests for settings, storage, touch decoding, touch state, UI and engine audio hooks. */
public class PlatformTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) < eps; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Renderer that only counts the quads it is asked to draw. */
    static final class QuadRenderer implements Renderer {
        int quads, calls;
        public String getBackendName() { return "test"; }
        public int getWidth() { return 960; }
        public int getHeight() { return 640; }
        public int getDrawCalls() { return 0; }
        public int getTriangles() { return 0; }
        public void setFog(boolean enabled, float start, float end, float r, float g, float b) { }
        public void drawSky(int mesh) { }
        public void drawBillboards(int texture, float[] data, int count, boolean additive) { }
        public int createMesh(MeshData d) { return 0; }
        public int loadTexture(String p, boolean s) { return 0; }
        public int loadTexture(String p, boolean s, boolean r) { return 0; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return true; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return 128; }
        public int getTextureHeight(int t) { return 128; }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean e) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float a, float b, float c, float d, float e, float f) { }
        public void clear(float r, float g, float b) { }
        public void setCamera(Mat4 p, Mat4 v) { }
        public void drawMesh(int m, Mat4 model) { }
        public void drawQuads(int t, float[] v, int n) { quads += n; calls++; }
    }

    /** Audio that records what the engine asks for. */
    static final class RecAudio implements Audio {
        int pauses, resumes, releases;
        public int loadSound(String p) { return 1; }
        public void unloadSound(int s) { }
        public int playSound(int s, float v, float p, boolean l) { return 1; }
        public void stopStream(int s) { }
        public boolean playMusic(String p, boolean l) { return true; }
        public void stopMusic() { }
        public void setMusicVolume(float v) { }
        public boolean isMusicPlaying() { return false; }
        public void pauseAll() { pauses++; }
        public void resumeAll() { resumes++; }
        public void release() { releases++; }
    }

    static final class Clicks implements Ui.Listener {
        int[] clicked = new int[64];
        int clicks;
        int valueChanges;
        float lastValue = -1f;
        int lastValueId = -1;
        public void onClick(int id) { if (clicks < clicked.length) clicked[clicks] = id; clicks++; }
        public void onValueChanged(int id, float v) { valueChanges++; lastValue = v; lastValueId = id; }
        int last() { return clicks == 0 ? -1 : clicked[clicks - 1]; }
    }

    static InputEvent ev(int type, int pointer, float x, float y) {
        InputEvent e = new InputEvent();
        e.type = type; e.pointer = pointer; e.x = x; e.y = y;
        return e;
    }

    public static void main(String[] args) {
        int D = InputEvent.DOWN, M = InputEvent.MOVE, U = InputEvent.UP;

        // ---------------- Settings
        Settings s = new Settings(8);
        s.putInt("score", 1234); s.putFloat("volume", 0.65f); s.putBoolean("music", true); s.putString("name", "Ann");
        check(s.getInt("score", 0) == 1234 && near(s.getFloat("volume", 0), 0.65f, 1e-6f) && s.getBoolean("music", false)
                && "Ann".equals(s.getString("name", "?")) && s.size() == 4, "settings: all four types store and read back");
        check(s.getInt("missing", 7) == 7 && s.getFloat("missing", 1.5f) == 1.5f && !s.getBoolean("missing", false)
                && "x".equals(s.getString("missing", "x")) && !s.contains("missing") && s.contains("score"), "settings: defaults for missing keys");
        check(s.getFloat("score", 9f) == 9f && s.getInt("volume", 8) == 8, "settings: a key read with the wrong type returns the default");
        s.putInt("score", 99);
        check(s.getInt("score", 0) == 99 && s.size() == 4, "settings: overwriting does not add an entry");
        Settings full = new Settings(2);
        check(full.putInt("a", 1) && full.putInt("b", 2) && !full.putInt("c", 3) && full.putInt("a", 5), "settings: full store rejects new keys but updates old ones");
        boolean threw = false;
        try { s.putInt("bad key", 1); } catch (IllegalArgumentException e) { threw = true; }
        boolean threw2 = false;
        try { s.putInt("", 1); } catch (IllegalArgumentException e) { threw2 = true; }
        check(threw && threw2, "settings: white space and empty keys are rejected");

        Settings tricky = new Settings(8);
        tricky.putString("text", "line1\nline2 \\ back\\slash\r end ä ß 日本");
        tricky.putFloat("f", 0.1f); tricky.putFloat("neg", -12345.678f); tricky.putInt("big", Integer.MIN_VALUE); tricky.putBoolean("off", false);
        tricky.putString("empty", "");
        MemoryStorage store = new MemoryStorage();
        check(tricky.save(store, "set.txt") && store.load("set.txt") != null, "settings: save writes a file");
        Settings back = new Settings(8);
        check(back.load(store, "set.txt") && back.size() == 6, "settings: load reads it back (6 entries)");
        check("line1\nline2 \\ back\\slash\r end ä ß 日本".equals(back.getString("text", null)) && back.getFloat("f", 0) == 0.1f
                && back.getFloat("neg", 0) == -12345.678f && back.getInt("big", 0) == Integer.MIN_VALUE
                && !back.getBoolean("off", true) && "".equals(back.getString("empty", "x")),
                "settings: round trip is exact (newlines, backslashes, unicode, floats, extremes, empty string)");
        check(!new Settings(4).load(store, "nothing.txt"), "settings: loading a missing file returns false");
        Settings damaged = new Settings(8);
        int read = damaged.fromBytes("i good 5\nthis is garbage\ni bad notanumber\nf also 2.5\nx unknown 1\n\nb flag 1\n".getBytes());
        check(read == 3 && damaged.getInt("good", 0) == 5 && damaged.getFloat("also", 0) == 2.5f && damaged.getBoolean("flag", false) && !damaged.contains("bad"),
                "settings: damaged lines are skipped, good ones are kept (" + read + " read)");
        tricky.clear();
        check(tricky.size() == 0 && !tricky.contains("text"), "settings: clear");

        // ---------------- MemoryStorage
        MemoryStorage ms = new MemoryStorage();
        byte[] data = {1, 2, 3};
        ms.save("a", data);
        data[0] = 99;
        byte[] got = ms.load("a");
        got[1] = 77;
        check(ms.load("a")[0] == 1 && ms.load("a")[1] == 2, "storage: data is copied on save and on load");
        check(ms.delete("a") && ms.load("a") == null && !ms.delete("a"), "storage: delete");

        // ---------------- TouchDecoder
        InputQueue q = new InputQueue(64);
        InputEvent e = new InputEvent();
        int[] ids = new int[16]; float[] xs = new float[16], ys = new float[16];
        ids[0] = 0; xs[0] = 10; ys[0] = 20;
        TouchDecoder.decode(TouchDecoder.ACTION_DOWN, 0, 1, ids, xs, ys, q);
        ids[0] = 0; ids[1] = 3; xs[0] = 11; ys[0] = 21; xs[1] = 100; ys[1] = 200;
        TouchDecoder.decode(TouchDecoder.ACTION_POINTER_DOWN, 1, 2, ids, xs, ys, q);
        TouchDecoder.decode(TouchDecoder.ACTION_MOVE, 0, 2, ids, xs, ys, q);
        TouchDecoder.decode(TouchDecoder.ACTION_POINTER_UP, 0, 2, ids, xs, ys, q);
        ids[0] = 3; xs[0] = 100; ys[0] = 200;
        TouchDecoder.decode(TouchDecoder.ACTION_UP, 0, 1, ids, xs, ys, q);
        int[] want = {D, 0, U, 0};
        int[][] expect = {{D, 0}, {D, 3}, {M, 0}, {M, 3}, {U, 0}, {U, 3}};
        boolean seq = q.size() == expect.length;
        for (int i = 0; seq && i < expect.length; i++) {
            q.poll(e);
            seq = e.type == expect[i][0] && e.pointer == expect[i][1];
        }
        check(seq, "decoder: two-finger gesture becomes DOWN0, DOWN3, MOVE0, MOVE3, UP0, UP3 with the right pointer ids");
        ids[0] = 5; xs[0] = 1; ys[0] = 2; ids[1] = 6; xs[1] = 3; ys[1] = 4;
        TouchDecoder.decode(TouchDecoder.ACTION_CANCEL, 0, 2, ids, xs, ys, q);
        int cancels = 0;
        while (q.poll(e)) if (e.type == U) cancels++;
        check(cancels == 2, "decoder: CANCEL releases every pointer");
        TouchDecoder.decode(TouchDecoder.ACTION_POINTER_DOWN, 5, 2, ids, xs, ys, q);
        TouchDecoder.decode(TouchDecoder.ACTION_POINTER_UP, -1, 2, ids, xs, ys, q);
        TouchDecoder.decode(TouchDecoder.ACTION_DOWN, 0, 0, ids, xs, ys, q);
        TouchDecoder.decode(99, 0, 2, ids, xs, ys, q);
        check(q.size() == 0, "decoder: invalid index, empty and unknown actions are ignored");
        ids[0] = 2; xs[0] = 33.5f; ys[0] = 44.5f;
        TouchDecoder.decode(TouchDecoder.ACTION_DOWN, 0, 1, ids, xs, ys, q);
        q.poll(e);
        check(e.pointer == 2 && e.x == 33.5f && e.y == 44.5f, "decoder: coordinates and pointer id are passed through");

        // ---------------- TouchState
        TouchState ts = new TouchState();
        ts.feed(ev(D, 0, 100, 100));
        check(ts.getDownCount() == 1 && ts.isDown(0) && !ts.isDown(1) && ts.getX(0) == 100f, "touch: one finger down");
        ts.feed(ev(M, 0, 130, 140));
        check(near(ts.getDragDistance(0), 50f, 1e-3f) && ts.getStartX(0) == 100f && ts.getPinchDistance() < 0f, "touch: drag distance, no pinch with one finger");
        ts.feed(ev(D, 4, 300, 100));
        check(ts.getDownCount() == 2 && ts.getPointerId(0) == 0 && ts.getPointerId(1) == 4 && ts.getPointerId(2) == -1, "touch: two fingers, ids ascend");
        ts.feed(ev(M, 4, 330, 140));
        ts.consumePinch();
        float d0 = ts.getPinchDistance();
        ts.feed(ev(M, 4, 430, 140));
        float d1 = ts.getPinchDistance();
        check(near(ts.consumePinch(), d1 - d0, 1e-3f) && d1 > d0 && ts.consumePinch() == 0f, "touch: pinch apart is positive and consumed once");
        ts.feed(ev(M, 4, 330, 140));
        check(ts.consumePinch() < 0f, "touch: pinch together is negative");
        ts.feed(ev(D, 7, 0, 0));
        check(ts.getDownCount() == 3 && ts.getPinchDistance() < 0f, "touch: three fingers are not a pinch");
        ts.feed(ev(U, 7, 0, 0)); ts.feed(ev(U, 0, 130, 140));
        check(ts.getDownCount() == 1 && !ts.isDown(0) && ts.isDown(4), "touch: fingers up");
        ts.feed(ev(U, 9, 0, 0)); ts.feed(ev(D, 99, 0, 0)); ts.feed(ev(M, -1, 0, 0));
        check(ts.getDownCount() == 1, "touch: unknown and out-of-range pointers are ignored");
        ts.reset();
        check(ts.getDownCount() == 0 && !ts.isDown(4), "touch: reset");

        // ---------------- UI
        QuadRenderer qr = new QuadRenderer();
        SpriteBatch batch = new SpriteBatch(qr, 1024);
        BitmapFont font = new BitmapFont(TextureRegion.grid(1, 128, 128, 8, 16, 16, 6), 32);
        TextureRegion white = new TextureRegion().set(2, 16, 16, 0, 0, 8, 8);
        Ui ui = new Ui(32, 24, batch, font, white);
        Clicks cl = new Clicks();
        ui.setListener(cl);
        ui.setViewport(480f, 320f, 480, 320);

        int bOk = ui.addButton("OK", 20f, 20f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f);
        int bBig = ui.addButton("BIG", 20f, 80f, 100f, 40f, Ui.LEFT, Ui.TOP, 2f);
        check(bOk == 0 && bBig == 1 && ui.getCount() == 2, "ui: widgets get consecutive ids");

        check(ui.handle(ev(D, 0, 50, 40)) && ui.isPressed(bOk), "ui: touching a button presses it and consumes the event");
        check(ui.handle(ev(M, 0, 55, 45)) && ui.handle(ev(U, 0, 55, 45)) && cl.clicks == 1 && cl.last() == bOk && !ui.isPressed(bOk), "ui: release inside = click, event consumed");
        ui.handle(ev(D, 0, 50, 40)); ui.handle(ev(M, 0, 300, 300)); 
        check(!ui.isPressed(bOk), "ui: pressed look goes away when the finger leaves the button");
        ui.handle(ev(U, 0, 300, 300));
        check(cl.clicks == 1, "ui: release outside = no click");
        ui.handle(ev(D, 0, 50, 40)); ui.handle(ev(M, 0, 300, 300)); ui.handle(ev(M, 0, 60, 50)); ui.handle(ev(U, 0, 60, 50));
        check(cl.clicks == 2 && cl.last() == bOk, "ui: sliding out and back in still clicks");
        check(!ui.handle(ev(D, 0, 400, 300)) && !ui.handle(ev(M, 0, 410, 300)) && !ui.handle(ev(U, 0, 410, 300)) && cl.clicks == 2,
                "ui: a touch that starts on empty space is never consumed (the game gets the whole gesture)");

        // stacking: later widgets are on top
        int top = ui.addButton("TOP", 20f, 20f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f);
        ui.handle(ev(D, 0, 50, 40)); ui.handle(ev(U, 0, 50, 40));
        check(cl.last() == top, "ui: the topmost (last created) widget gets the touch");
        ui.setVisible(top, false);
        ui.handle(ev(D, 0, 50, 40)); ui.handle(ev(U, 0, 50, 40));
        check(cl.last() == bOk, "ui: hidden widgets are not touchable");
        ui.setVisible(top, true);

        // disabled
        int dis = ui.addButton("OFF", 200f, 20f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f);
        ui.setEnabled(dis, false);
        int before = cl.clicks;
        boolean eaten = ui.handle(ev(D, 0, 230, 40)) && ui.handle(ev(U, 0, 230, 40));
        check(eaten && cl.clicks == before && !ui.isPressed(dis), "ui: a disabled button swallows the touch but does nothing");
        ui.setEnabled(dis, true);

        // toggle
        int tg = ui.addToggle("SND", 200f, 80f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f, true);
        check(ui.isChecked(tg), "ui: toggle starts checked");
        ui.handle(ev(D, 0, 230, 100)); ui.handle(ev(U, 0, 230, 100));
        check(!ui.isChecked(tg) && cl.last() == tg, "ui: toggle flips on click");
        ui.handle(ev(D, 0, 230, 100)); ui.handle(ev(M, 0, 0, 0)); ui.handle(ev(U, 0, 0, 0));
        check(!ui.isChecked(tg), "ui: toggle does not flip when released outside");
        ui.setChecked(tg, true);
        check(ui.isChecked(tg), "ui: setChecked");

        // slider
        int sl = ui.addSlider(200f, 140f, 200f, 24f, Ui.LEFT, Ui.TOP, 2f);
        check(ui.getValue(sl) == 1f, "ui: initial slider value is clamped");
        ui.setValue(sl, 0.5f);
        int vc0 = cl.valueChanges;
        ui.handle(ev(D, 0, 250, 150));
        check(near(ui.getValue(sl), 0.25f, 1e-4f) && cl.lastValueId == sl && near(cl.lastValue, 0.25f, 1e-4f), "ui: touching a slider sets the value from the position");
        ui.handle(ev(M, 0, 340, 400));
        check(near(ui.getValue(sl), 0.7f, 1e-4f), "ui: dragging follows the finger even outside the slider vertically");
        ui.handle(ev(M, 0, 900, 150));
        check(ui.getValue(sl) == 1f, "ui: slider value is clamped at the end");
        ui.handle(ev(M, 0, -50, 150));
        check(ui.getValue(sl) == 0f, "ui: slider value is clamped at the start");
        ui.handle(ev(U, 0, -50, 150));
        int vc1 = cl.valueChanges;
        ui.handle(ev(D, 0, 250, 150)); ui.handle(ev(M, 0, 250, 150)); ui.handle(ev(U, 0, 250, 150));
        check(cl.valueChanges == vc1 + 1 && vc1 > vc0, "ui: the listener is only called when the value really changes");

        // panels
        int bg = ui.addPanel(400f, 20f, 60f, 60f, Ui.LEFT, Ui.TOP);
        check(!ui.handle(ev(D, 0, 420, 40)), "ui: a normal panel does not block touches");
        ui.setBlocking(bg, true);
        check(ui.handle(ev(D, 0, 420, 40)) && ui.handle(ev(U, 0, 420, 40)), "ui: a blocking panel swallows touches");
        ui.setBlocking(bg, false);

        // pixel -> virtual conversion
        Ui hd = new Ui(4, 16, batch, font, white);
        Clicks hdc = new Clicks();
        hd.setListener(hdc);
        hd.setViewport(480f, 320f, 960, 640);
        int hb = hd.addButton("X", 10f, 10f, 100f, 50f, Ui.LEFT, Ui.TOP, 1f);
        hd.handle(ev(D, 0, 130, 50)); hd.handle(ev(U, 0, 130, 50));
        check(hdc.clicks == 1 && hdc.last() == hb, "ui: touch pixels are converted to virtual units (2x screen)");
        hd.handle(ev(D, 0, 250, 50)); hd.handle(ev(U, 0, 250, 50));
        check(hdc.clicks == 1, "ui: pixel 250 = virtual 125 is outside");

        // anchors
        Ui an = new Ui(8, 16, batch, font, white);
        an.setViewport(600f, 320f, 600, 320);
        int br = an.addButton("R", -10f, -10f, 80f, 30f, Ui.RIGHT, Ui.BOTTOM, 1f);
        int cc = an.addButton("C", 0f, 0f, 100f, 40f, Ui.CENTER, Ui.MIDDLE, 1f);
        int rc = an.addButton("RC", -5f, 15f, 60f, 20f, Ui.RIGHT, Ui.TOP, 1f);
        check(near(an.getRectX(br), 510f, 1e-3f) && near(an.getRectY(br), 280f, 1e-3f), "ui: RIGHT/BOTTOM anchor with negative offsets (rect " + an.getRectX(br) + ", " + an.getRectY(br) + ")");
        check(near(an.getRectX(cc), 250f, 1e-3f) && near(an.getRectY(cc), 140f, 1e-3f), "ui: CENTER/MIDDLE anchor centers the widget");
        check(near(an.getRectX(rc), 535f, 1e-3f) && near(an.getRectY(rc), 15f, 1e-3f), "ui: RIGHT/TOP anchor");
        an.setViewport(800f, 400f, 800, 400);
        check(near(an.getRectX(br), 710f, 1e-3f) && near(an.getRectY(br), 360f, 1e-3f) && near(an.getRectX(cc), 350f, 1e-3f) && near(an.getRectY(cc), 180f, 1e-3f),
                "ui: setViewport re-lays out every widget for the new screen shape");

        // labels measure their text after drawing
        int lb = ui.addLabel("HELLO", 0f, 5f, Ui.CENTER, Ui.TOP, 2f);
        ui.setText(lb, "HELLO");
        batch.begin(new Mat4().setOrtho(0, 480, 320, 0, -1, 1));
        ui.draw();
        batch.end();
        check(near(ui.getRectW(lb), 5 * 8 * 2f, 1e-3f) && near(ui.getRectH(lb), 32f, 1e-3f) && near(ui.getRectX(lb), 240f - 40f, 1e-3f), "ui: label size follows its text (5 chars x 8 x scale 2) and CENTER anchor");
        ui.text(lb).clear().append("A\nBBBBBBB");
        batch.begin(new Mat4().setOrtho(0, 480, 320, 0, -1, 1));
        ui.draw();
        batch.end();
        check(near(ui.getRectW(lb), 7 * 8 * 2f, 1e-3f) && near(ui.getRectH(lb), 64f, 1e-3f), "ui: two-line label is as wide as its longest line (text changed in place)");
        check(qr.quads > 0 && qr.calls >= 2, "ui: draw produces sprites (" + qr.quads + " quads)");

        // pages
        Ui pg = new Ui(8, 16, batch, font, white);
        Clicks pgc = new Clicks();
        pg.setListener(pgc);
        pg.setViewport(480f, 320f, 480, 320);
        int always = pg.addPanel(0f, 0f, 10f, 10f, Ui.LEFT, Ui.TOP);
        int p0 = pg.addButton("P0", 100f, 100f, 80f, 40f, Ui.LEFT, Ui.TOP, 1f);
        int p1 = pg.addButton("P1", 100f, 100f, 80f, 40f, Ui.LEFT, Ui.TOP, 1f);
        pg.setGroup(p0, 0); pg.setGroup(p1, 1);
        check(pg.isShown(always) && pg.isShown(p0) && !pg.isShown(p1) && pg.getActiveGroup() == 0, "ui: widgets of the active page and ALWAYS widgets are shown");
        pg.handle(ev(D, 0, 120, 120)); pg.handle(ev(U, 0, 120, 120));
        check(pgc.last() == p0, "ui: touches go to the active page");
        pg.showGroup(1);
        check(!pg.isShown(p0) && pg.isShown(p1) && pg.isShown(always), "ui: showGroup switches pages");
        pg.handle(ev(D, 0, 120, 120)); pg.handle(ev(U, 0, 120, 120));
        check(pgc.last() == p1, "ui: after switching, the same spot belongs to the other page");
        pg.handle(ev(D, 0, 120, 120));
        pg.showGroup(0);
        check(!pg.isPressed(p1), "ui: switching pages releases pressed widgets");
        int cBefore = pgc.clicks;
        pg.handle(ev(U, 0, 120, 120));
        check(pgc.clicks == cBefore, "ui: the stray release after a page switch causes no click");

        // a click that changes the page (typical menu button)
        final Ui menu = new Ui(8, 16, batch, font, white);
        menu.setViewport(480f, 320f, 480, 320);
        final int play = menu.addButton("PLAY", 100f, 100f, 80f, 40f, Ui.LEFT, Ui.TOP, 1f);
        final int backBtn = menu.addButton("BACK", 100f, 100f, 80f, 40f, Ui.LEFT, Ui.TOP, 1f);
        menu.setGroup(play, 0); menu.setGroup(backBtn, 1);
        final int[] hits = new int[2];
        menu.setListener(new Ui.Listener() {
            public void onClick(int id) { hits[id == play ? 0 : 1]++; menu.showGroup(id == play ? 1 : 0); }
            public void onValueChanged(int id, float v) { }
        });
        menu.handle(ev(D, 0, 120, 120)); menu.handle(ev(U, 0, 120, 120));
        menu.handle(ev(D, 0, 120, 120)); menu.handle(ev(U, 0, 120, 120));
        check(hits[0] == 1 && hits[1] == 1 && menu.getActiveGroup() == 0, "ui: a button may switch the page from inside its own click (PLAY -> BACK -> menu)");

        // multi-touch: two buttons at the same time
        Ui mt = new Ui(8, 16, batch, font, white);
        Clicks mtc = new Clicks();
        mt.setListener(mtc);
        mt.setViewport(480f, 320f, 480, 320);
        int ma = mt.addButton("A", 20f, 20f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f);
        int mb = mt.addButton("B", 200f, 20f, 100f, 40f, Ui.LEFT, Ui.TOP, 1f);
        mt.handle(ev(D, 0, 50, 40)); mt.handle(ev(D, 1, 250, 40));
        check(mt.isPressed(ma) && mt.isPressed(mb), "ui multi-touch: two fingers press two buttons at once");
        mt.handle(ev(M, 1, 400, 300));
        check(mt.isPressed(ma) && !mt.isPressed(mb), "ui multi-touch: moving one finger away only cancels its own button");
        mt.handle(ev(U, 0, 50, 40)); mt.handle(ev(U, 1, 400, 300));
        check(mtc.clicks == 1 && mtc.last() == ma, "ui multi-touch: only the button that was released inside clicks");
        mt.handle(ev(D, 3, 50, 40)); mt.handle(ev(D, 5, 250, 40)); mt.handle(ev(U, 5, 250, 40)); mt.handle(ev(U, 3, 50, 40));
        check(mtc.clicks == 3, "ui multi-touch: releasing in any order gives a click each");
        check(!mt.handle(ev(D, 99, 50, 40)) && !mt.handle(ev(D, -1, 50, 40)), "ui multi-touch: pointer ids outside the supported range are ignored");
        mt.clear();
        check(mt.getCount() == 0, "ui: clear");
        Ui tiny = new Ui(1, 8, batch, font, white);
        check(tiny.addLabel("a", 0, 0, Ui.LEFT, Ui.TOP, 1f) == 0 && tiny.addLabel("b", 0, 0, Ui.LEFT, Ui.TOP, 1f) == -1, "ui: add returns -1 when full");

        // ---------------- engine: audio and storage hooks
        Engine eng = new Engine();
        check(eng.getAudio() != null && eng.getStorage() != null && !eng.getAudio().isMusicPlaying(), "engine: audio and storage are never null (null object defaults)");
        RecAudio ra = new RecAudio();
        eng.setAudio(ra);
        final int[] screenCalls = new int[2];
        eng.setScreen(new ScreenAdapter() {
            public void update() { }
            public void render(Renderer r) { }
            public void pause() { screenCalls[0]++; }
            public void resume() { screenCalls[1]++; }
        });
        eng.frame(1000000000L, qr);
        eng.pause();
        eng.resume();
        check(ra.pauses == 1 && ra.resumes == 1 && screenCalls[0] == 1 && screenCalls[1] == 1, "engine: pause() and resume() reach the audio and the screen");
        eng.setAudio(null);
        eng.setStorage(null);
        check(eng.getAudio() != null && eng.getStorage() != null, "engine: setting null falls back to the defaults");
        Audio na = new NullAudio();
        check(na.loadSound("x") == 0 && na.playSound(0, 1, 1, false) == -1 && na.playMusic("m", true) && !na.isMusicPlaying(), "audio: NullAudio accepts every call");

        // ---------------- zero allocation: draw + touch + text updates for 100000 frames
        final Ui hot = new Ui(16, 32, batch, font, white);
        hot.setViewport(480f, 320f, 960, 640);
        hot.setListener(new Ui.Listener() { public void onClick(int id) { } public void onValueChanged(int id, float v) { } });
        int hb0 = hot.addButton("PLAY", 100f, 100f, 100f, 40f, Ui.CENTER, Ui.MIDDLE, 2f);
        int hl = hot.addLabel("FPS 60", 6f, 6f, Ui.LEFT, Ui.TOP, 1f);
        int ht = hot.addToggle("SOUND", -10f, 10f, 100f, 30f, Ui.RIGHT, Ui.TOP, 1f, true);
        int hs = hot.addSlider(10f, -20f, 200f, 24f, Ui.LEFT, Ui.BOTTOM, 0.5f);
        hot.addPanel(0f, 0f, 480f, 20f, Ui.LEFT, Ui.BOTTOM);
        Mat4 proj = new Mat4().setOrtho(0, 480, 320, 0, -1, 1);
        InputEvent hev = new InputEvent();
        TouchState hts = new TouchState();
        for (int i = 0; i < 20000; i++) {
            hot.text(hl).clear().append("FPS ").append(i);
            batch.begin(proj); hot.draw(); batch.end();
        }
        long b0 = allocated();
        for (int i = 0; i < 100000; i++) {
            hot.text(hl).clear().append("FPS ").append(i % 100).append(" T ").append(i * 0.5f, 1);
            hev.type = (i % 3 == 0) ? D : ((i % 3 == 1) ? M : U);
            hev.pointer = i & 3;
            hev.x = (i * 7) % 960; hev.y = (i * 13) % 640;
            hts.feed(hev);
            if (!hot.handle(hev)) { hts.getPinchDistance(); hts.consumePinch(); }
            batch.begin(proj); hot.draw(); batch.end();
        }
        long delta = allocated() - b0;
        check(delta < 4096, "zero allocation: 100000 frames of ui draw + touch + text updates allocated " + delta + " bytes");

        System.out.println(failed == 0 ? "ALL PLATFORM TESTS PASSED" : (failed + " PLATFORM TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
