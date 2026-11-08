// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

public class DemoSmokeTest {
    static final class R implements Renderer {
        int draws, quads, cams;
        public String getBackendName() { return "test"; }
        public int getWidth() { return 800; }
        public int getHeight() { return 480; }
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
        public boolean usesBufferObjects() { return true; }
        public void setLighting(boolean e) {}
        public void setAmbientLight(float r, float g, float b) {}
        public void setDirectionalLight(float a, float b, float c, float d, float e, float f) {}
        public void clear(float r, float g, float b) {}
        public void setCamera(Mat4 p, Mat4 v) { cams++; }
        public void drawMesh(int m, Mat4 model) { draws++; }
        public void drawQuads(int t, float[] v, int n) { quads += n; }
    }

    static void run(String name, Screen screen, int[][] script) {
        run(name, screen, script, null);
    }

    static void run(String name, Screen screen, int[][] script, Assets assets) {
        Engine e = new Engine();
        e.setAssets(assets);
        runOn(name, e, screen, script, 4000);
    }

    /** Runs a screen on the given engine; script entries are {frame, type, x, y [, pointer]}. */
    static R runOn(String name, Engine e, Screen screen, int[][] script, int frames) {
        R r = new R();
        e.setScreen(screen);
        long t = 1000000000L;
        for (int f = 0; f < frames; f++) {
            t += 16666667L;
            for (int[] s : script) {
                if (s[0] == f) e.input.push(s[1], s.length > 4 ? s[4] : 0, s[2], s[3]);
            }
            e.frame(t, r);
        }
        System.out.println(name + ": OK, draws=" + r.draws + " quads=" + r.quads + " ticks=" + e.getTotalTicks());
        return r;
    }

    /** Audio that counts what the demo asks for. */
    static final class CountAudio implements Audio {
        int loads, plays, musicStarts, musicStops, pauses, resumes, releases;
        float musicVolume = -1f;
        public int loadSound(String p) { loads++; return loads; }
        public void unloadSound(int s) { }
        public int playSound(int s, float v, float p, boolean l) { plays++; return plays; }
        public void stopStream(int s) { }
        public boolean playMusic(String p, boolean l) { musicStarts++; return true; }
        public void stopMusic() { musicStops++; }
        public void setMusicVolume(float v) { musicVolume = v; }
        public boolean isMusicPlaying() { return musicStarts > musicStops; }
        public void pauseAll() { pauses++; }
        public void resumeAll() { resumes++; }
        public void release() { releases++; }
    }

    static int smokeFailures;

    static void expect(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
        if (!ok) smokeFailures++;
    }

    public static void main(String[] a) throws Exception {
        // physics demo: taps (kick/spawn), buttons MORE, GRID, RESET, drag
        int D = InputEvent.DOWN, U = InputEvent.UP, M = InputEvent.MOVE;
        int[][] script = {
            {100, D, 400, 240}, {101, U, 400, 240},
            {200, D, 700, 300}, {201, U, 700, 300},
            {300, D, 300, 440}, {301, U, 300, 440},          // MORE
            {400, D, 300, 440}, {401, U, 300, 440},          // MORE
            {500, D, 150, 440}, {501, U, 150, 440},          // GRID off
            {900, D, 600, 440}, {901, U, 600, 440},          // RESET
            {1000, D, 100, 100}, {1001, M, 140, 100}, {1002, M, 200, 120}, {1003, U, 200, 120},
            {1100, D, 150, 440}, {1101, U, 150, 440},        // GRID on
            {1200, D, 300, 440}, {1201, U, 300, 440}, {1300, D, 300, 440}, {1301, U, 300, 440},
            {1400, D, 300, 440}, {1401, U, 300, 440}, {1500, D, 300, 440}, {1501, U, 300, 440},
            {1600, D, 300, 440}, {1601, U, 300, 440}, {1700, D, 300, 440}, {1701, U, 300, 440}, // exceed capacity
        };
        run("physics demo", new skarph.demo.v04.PhysicsDemoScreen(), script);
        int[][] levelScript = {
            {100, D, 400, 240}, {101, U, 400, 240}, {150, D, 300, 200}, {151, U, 300, 200},
            {200, D, 100, 440}, {201, U, 100, 440},          // LMAP off
            {300, D, 300, 440}, {301, U, 300, 440},          // LIGHT off
            {400, D, 600, 440}, {401, U, 600, 440},          // RELOAD
            {600, D, 600, 440}, {601, U, 600, 440},          // RELOAD again
            {800, D, 100, 440}, {801, U, 100, 440}, {900, D, 300, 440}, {901, U, 300, 440},
            {1000, D, 100, 100}, {1001, M, 160, 100}, {1002, M, 260, 130}, {1003, U, 260, 130}};
        // ---- menu demo: menu, settings (saved), touch lab with two fingers, restart with the saved state
        // screen 800x480 -> 1.5 pixels per virtual unit, virtual width 533.3
        int[][] menuScript = {
            {100, D, 400, 315}, {101, U, 400, 315},                       // SETTINGS
            {150, D, 250, 177}, {151, U, 250, 177},                       // music slider -> ~0.19
            {160, D, 500, 264}, {161, U, 500, 264},                       // effects slider -> ~0.71
            {170, D, 277, 333}, {171, U, 277, 333},                       // MUSIC toggle off
            {180, D, 527, 333}, {181, U, 527, 333},                       // EFFECTS toggle off
            {190, D, 527, 333}, {191, U, 527, 333},                       // EFFECTS toggle on
            {200, D, 277, 333}, {201, U, 277, 333},                       // MUSIC toggle on
            {250, D, 400, 399}, {251, U, 400, 399},                       // BACK (saves)
            {300, D, 400, 225}, {301, U, 400, 225},                       // PLAY
            {350, D, 300, 300}, {351, U, 300, 300},                       // tap: coin
            {360, D, 300, 300}, {361, U, 300, 300},                       // tap: coin
            {400, D, 200, 200, 0}, {400, D, 500, 300, 1},                 // two fingers down
            {401, M, 200, 200, 0}, {401, M, 600, 300, 1},
            {402, M, 150, 200, 0}, {402, M, 700, 300, 1},                 // pinch apart
            {410, U, 150, 200, 0}, {410, U, 700, 300, 1},
            {450, D, 705, 39}, {451, U, 705, 39}};                        // BACK to the menu
        Engine me = new Engine();
        me.setAssets(new ContentTest.FileAssets("demos/demo-v0.6/assets"));
        CountAudio ca = new CountAudio();
        me.setAudio(ca);
        runOn("menu demo", me, new skarph.demo.v06.MenuDemoScreen(), menuScript, 600);
        me.pause();
        me.resume();
        Settings saved = new Settings(16);
        expect(saved.load(me.getStorage(), "settings.txt"), "settings file was written on BACK / pause");
        expect(Math.abs(saved.getFloat("musicVolume", -1) - 0.1875f) < 0.02f, "music slider value saved (" + saved.getFloat("musicVolume", -1) + ")");
        expect(Math.abs(saved.getFloat("sfxVolume", -1) - 0.708f) < 0.02f, "effects slider value saved (" + saved.getFloat("sfxVolume", -1) + ")");
        expect(saved.getBoolean("musicOn", false) && saved.getBoolean("sfxOn", false), "both toggles ended up ON again");
        expect(saved.getInt("rounds", 0) == 1, "one round played");
        expect(saved.getInt("coins", 0) == 4, "4 coins collected: 2 taps + 2 fingers (" + saved.getInt("coins", 0) + ")");
        expect(ca.loads == 4, "4 sounds loaded (" + ca.loads + ")");
        expect(ca.plays >= 12, "sound effects played for clicks, toggles, slider ticks and coins (" + ca.plays + ")");
        expect(ca.musicStarts == 2 && ca.musicStops == 1, "music: started, stopped by the toggle, started again (" + ca.musicStarts + "/" + ca.musicStops + ")");
        expect(Math.abs(ca.musicVolume - 0.1875f) < 0.02f, "music volume follows the slider (" + ca.musicVolume + ")");
        expect(ca.pauses == 1 && ca.resumes == 1, "engine pause/resume reached the audio");

        // restart: a new engine with the same storage must come back with the saved state
        Engine re = new Engine();
        re.setAssets(new ContentTest.FileAssets("demos/demo-v0.6/assets"));
        re.setStorage(me.getStorage());
        CountAudio ca2 = new CountAudio();
        re.setAudio(ca2);
        runOn("menu demo after restart", re, new skarph.demo.v06.MenuDemoScreen(), new int[][] {}, 60);
        expect(Math.abs(ca2.musicVolume - 0.1875f) < 0.02f && ca2.musicStarts == 1, "restart: saved music volume restored, music started (" + ca2.musicVolume + ")");

        // ---- backends demo: two backends, one scene: buttons LMAP, LIGHT, RELOAD, backend switch (y 443 px, x 100/286/472/658)
        final int[] requested = {0, 0};
        skarph.demo.v07.BackendSwitcher sw = new skarph.demo.v07.BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int backend) { requested[0] = backend; requested[1]++; }
        };
        int[][] v7 = {{100, D, 100, 443}, {101, U, 100, 443}, {150, D, 286, 443}, {151, U, 286, 443},
                {200, D, 472, 443}, {201, U, 472, 443}, {300, D, 400, 240}, {301, U, 400, 240}, {400, D, 658, 443}, {401, U, 658, 443}};
        run("backends demo", new skarph.demo.v07.BackendDemoScreen(sw), v7, new ContentTest.FileAssets("demos/demo-v0.7/assets"));
        expect(requested[1] == 1 && requested[0] == 2, "backends demo: the backend button asks for OpenGL ES 2.0 (" + requested[0] + ", " + requested[1] + " request)");
        final int[] requested2 = {0, 0};
        skarph.demo.v07.BackendSwitcher none = new skarph.demo.v07.BackendSwitcher() {
            public boolean isGles2Available() { return false; }
            public void switchBackend(int backend) { requested2[1]++; }
        };
        run("backends demo without ES 2.0", new skarph.demo.v07.BackendDemoScreen(none), v7, new ContentTest.FileAssets("demos/demo-v0.7/assets"));
        expect(requested2[1] == 0, "backends demo: the backend button is disabled when ES 2.0 is not available");

        // ---- town demo. Buttons (px): FOG 87,442  SKY 246,442  LMAP 405,442  BACKEND 577,442  BENCH 713,442;
        //      CUT 87,382  RESTART 246,382  FX 405,382  PHYS 577,382;  QUAL 87,322  AUTO 246,322
        final int[] requested8 = {0, 0};
        skarph.demo.v08.BackendSwitcher sw8 = new skarph.demo.v08.BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int backend) { requested8[0] = backend; requested8[1]++; }
            public int getGcCount() { return 0; }
        };
        int[][] v8 = {{100, D, 87, 442}, {101, U, 87, 442}, {150, D, 246, 442}, {151, U, 246, 442}, {200, D, 405, 442}, {201, U, 405, 442},
                {250, D, 87, 382}, {251, U, 87, 382}, {300, D, 400, 200}, {301, M, 500, 200}, {302, U, 500, 200},      // CUT off, drag the orbit
                {350, D, 87, 382}, {351, U, 87, 382}, {400, D, 405, 382}, {401, U, 405, 382}, {450, D, 577, 382}, {451, U, 577, 382},
                {500, D, 87, 322}, {501, U, 87, 322}, {550, D, 87, 322}, {551, U, 87, 322}, {600, D, 246, 322}, {601, U, 246, 322},
                {700, D, 713, 442}, {701, U, 713, 442},                                     // BENCH: a full run starts
                {900, D, 87, 442}, {901, U, 87, 442},                                       // FOG while it runs: disabled, ignored
                {1000, D, 713, 442}, {1001, U, 713, 442},                                   // STOP
                {1100, D, 713, 442}, {1101, U, 713, 442},                                   // BENCH again, runs to its end (4000 frames)
                {3400, D, 577, 442}, {3401, U, 577, 442}};                                  // backend (enabled again after the run)
        run("town demo", new skarph.demo.v08.StressDemoScreen(sw8), v8, new ContentTest.FileAssets("demos/demo-v0.8/assets"));
        expect(requested8[1] == 1 && requested8[0] == 2, "town demo: the backend button asks for ES 2.0 once, only when no benchmark runs (" + requested8[1] + " request)");

        // ---- port demo. Menu START 400,231. HUD (px): FOG 87,442  SKY 246,442  LMAP 405,442  BACKEND 577,442  BENCH 713,442;
        //      CUT 87,382  RESTART 246,382  FX 405,382  SFX 564,382;  QUAL 87,322  AUTO 246,322  PHYS 405,322
        final int[] requested10 = {0, 0};
        skarph.demo.v10.BackendSwitcher sw10 = new skarph.demo.v10.BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int backend) { requested10[0] = backend; requested10[1]++; }
            public int getGcCount() { return 0; }
        };
        int[][] v10 = {{50, D, 400, 231}, {51, U, 400, 231},                                    // START
                {100, D, 87, 442}, {101, U, 87, 442}, {150, D, 246, 442}, {151, U, 246, 442}, {200, D, 405, 442}, {201, U, 405, 442},
                {250, D, 87, 382}, {251, U, 87, 382}, {300, D, 400, 200}, {301, M, 500, 200}, {302, U, 500, 200},      // CUT off, drag the orbit
                {350, D, 87, 382}, {351, U, 87, 382}, {400, D, 405, 382}, {401, U, 405, 382}, {450, D, 564, 382}, {451, U, 564, 382},
                {500, D, 405, 322}, {501, U, 405, 322}, {550, D, 87, 322}, {551, U, 87, 322}, {600, D, 87, 322}, {601, U, 87, 322},
                {650, D, 246, 322}, {651, U, 246, 322},
                {700, D, 713, 442}, {701, U, 713, 442},                                         // BENCH: a full run starts
                {900, D, 87, 442}, {901, U, 87, 442},                                           // FOG while it runs: disabled, ignored
                {1000, D, 713, 442}, {1001, U, 713, 442},                                       // STOP
                {1100, D, 713, 442}, {1101, U, 713, 442},                                       // BENCH again, runs to its end (4000 frames)
                {3400, D, 577, 442}, {3401, U, 577, 442}};                                      // backend (enabled again after the run)
        run("port demo", new skarph.demo.v10.PortDemoScreen(sw10), v10, new ContentTest.FileAssets("demos/demo-v1.0/assets"));
        expect(requested10[1] == 1 && requested10[0] == 2, "port demo: the backend button asks for ES 2.0 once, only when no benchmark runs (" + requested10[1] + " request)");

        run("level demo", new skarph.demo.v05.LevelDemoScreen(), levelScript, new ContentTest.FileAssets("demos/demo-v0.5/assets"));
        run("scene demo", new skarph.demo.v03.SceneDemoScreen(), new int[][] {{50, D, 100, 440}, {51, U, 100, 440}, {80, D, 300, 440}, {81, U, 300, 440}, {120, D, 100, 100}, {121, M, 300, 100}, {122, U, 300, 100}});
        run("sprites demo", new skarph.demo.v02.SpriteDemoScreen(), new int[][] {{50, D, 100, 100}, {51, M, 200, 120}, {52, U, 200, 120}});
        run("cubes demo", new skarph.demo.v01.CubeDemoScreen(), new int[][] {{50, D, 100, 100}, {51, M, 200, 120}, {52, U, 200, 120}});
        if (smokeFailures > 0) {
            System.out.println(smokeFailures + " SMOKE CHECK(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL SMOKE CHECKS PASSED");
    }
}
