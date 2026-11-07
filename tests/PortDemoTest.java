// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.demo.v10.*;
import skarph.core.*;

/**
 * Headless test of the port demo screen (plain Java, needs no android.jar): loads the real map assets, checks the menu page,
 * START / FREE ORBIT / MENU, fog / sky / lightmap / backend requests, the cutscene (the camera follows the baked track
 * tick by tick, loops, RESTART and CUT buttons), the effects (snow, halos, beam, sparks, shadows, fireworks, flashes),
 * the sound (music, wind, effects on cue, SFX / MUSIC switches, saved settings), the physics show, the quality levels,
 * the benchmark, and that a steady frame allocates (almost) nothing.
 */
public class PortDemoTest {
    static int failed;
    static final int FRAMES = 8000;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static final class R implements Renderer {
        int clears, meshDraws, skyDraws, fogOn, fogOff, lmapOn, lmapOff, quads, meshes, maxMeshDraws;
        int bbCalls, bbAdditive, bbFlat, bbRecords;
        final int[] bbFrame = new int[FRAMES];
        final float[] eyeX = new float[FRAMES], eyeY = new float[FRAMES], eyeZ = new float[FRAMES];
        boolean fogNow, lastFogCalled;
        float lastFogEnd;
        public String getBackendName() { return "test OpenGL ES 1.x"; }
        public int getWidth() { return 800; }
        public int getHeight() { return 480; }
        public int getDrawCalls() { return meshDraws; }
        public int getTriangles() { return meshDraws * 100; }
        public void setFog(boolean e, float s, float en, float r, float g, float b) { if (e) { fogOn++; lastFogEnd = en; } else fogOff++; fogNow = e; }
        public void drawSky(int mesh) { skyDraws++; }
        public void drawBillboards(int t, float[] d, int n, boolean a) {
            bbCalls++; bbRecords += n; if (a) bbAdditive++;
            for (int i = 0; i < n; i++) if (d[i * 13 + 4] < 0f) bbFlat++;
            if (clears > 0 && clears <= FRAMES) bbFrame[clears - 1] += n;
        }
        public int createMesh(MeshData d) { return meshes++; }
        public int loadTexture(String p, boolean s) { return 1; }
        public int loadTexture(String p, boolean s, boolean r) { return 1; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return true; }
        public void setLightmapsEnabled(boolean e) { if (e) lmapOn++; else lmapOff++; }
        public int getTextureWidth(int t) { return 128; }
        public int getTextureHeight(int t) { return 128; }
        public boolean usesBufferObjects() { return true; }
        public void setLighting(boolean e) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float a, float b, float c, float d, float e, float f) { }
        public void clear(float r, float g, float b) { if (meshDraws > maxMeshDraws) maxMeshDraws = meshDraws; if (clears < FRAMES) { eyeX[clears] = Float.NaN; } clears++; meshDraws = 0; }
        public void setCamera(Mat4 p, Mat4 v) {
            // eye = -(R^T * t) of the column major view matrix; stored for the frame that is being drawn (clears - 1)
            int f = clears - 1;
            if (f >= 0 && f < FRAMES && eyeX[f] != eyeX[f]) {           // the first camera of the frame (the HUD sets an identity view later)
                float[] m = v.m;
                eyeX[f] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
                eyeY[f] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
                eyeZ[f] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
            }
        }
        public void drawMesh(int m, Mat4 model) { meshDraws++; }
        public void drawQuads(int t, float[] v, int n) { quads += n; }
    }

    /** Records what the demo asks of the sound output: handles are the load order (0 = click ... 7 = wind). */
    static final class RecAudio implements Audio {
        final java.util.ArrayList<String> loaded = new java.util.ArrayList<String>();
        final int[] plays = new int[16];
        int loopStarts, stopStreams, musicStarts, musicStops, unloads;
        String music;
        boolean musicLoop, musicOn;
        public int loadSound(String p) { loaded.add(p); return loaded.size() - 1; }
        public void unloadSound(int s) { unloads++; }
        public int playSound(int s, float v, float p, boolean loop) { plays[s]++; if (loop) loopStarts++; return 100 + s; }
        public void stopStream(int st) { stopStreams++; }
        public boolean playMusic(String p, boolean loop) { music = p; musicLoop = loop; musicOn = true; musicStarts++; return true; }
        public void stopMusic() { musicOn = false; musicStops++; }
        public void setMusicVolume(float v) { }
        public boolean isMusicPlaying() { return musicOn; }
        public void pauseAll() { }
        public void resumeAll() { }
        public void release() { }
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "demos/demo-v1.0/assets";
        final int[] req = new int[2];
        BackendSwitcher sw = new BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int b) { req[0] = b; req[1]++; }
            public int getGcCount() { return 3; }
        };
        Engine e = new Engine();
        e.setAssets(new ContentTest.FileAssets(dir));
        RecAudio au = new RecAudio();
        e.setAudio(au);
        PortDemoScreen screen = new PortDemoScreen(sw);
        R r = new R();
        e.setScreen(screen);
        int D = InputEvent.DOWN, U = InputEvent.UP;

        CameraTrack track = CameraTrack.parse(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "cutscene.cam")));
        Timeline tl = Timeline.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "cutscene.tl")), "UTF-8"));
        check(tl.getSceneCount() == 5 && tl.getEndTick() == 2040 && track.getTickCount() >= tl.getEndTick(),
                "cutscene assets: 5 scenes, 34 s, the track covers the timeline");
        check("FLYOVER".equals(tl.getName(0)) && "FINALE".equals(tl.getName(4)), "cutscene assets: scene names FLYOVER ... FINALE");

        // pixel positions on 800 x 480 (1.5 px per virtual unit): menu START (400, 231), FREE ORBIT (400, 309), backend (400, 387);
        // HUD MENU (713, 38), CUT (87, 382), RESTART (246, 382), FOG (87, 442), SKY (246, 442), LMAP (405, 442), backend (577, 442)
        long t = 1000000000L;
        int f = 0;
        for (; f < 120; f++) { t += 16666667L; e.frame(t, r); }
        check(screen.isMenuShown() && screen.isCutscene(), "menu: the menu page is shown first, over the running cutscene");
        check(screen.getCutTick() >= 115 && screen.getCutTick() <= 125, "menu: the cutscene runs behind the menu (tick " + screen.getCutTick() + ")");
        check(r.skyDraws > 100 && r.fogOn > 100 && r.meshDraws > 0, "menu: sky, fog and the map are drawn behind the menu");

        check(au.loaded.size() == 8 && "click.wav".equals(au.loaded.get(0)) && "wind.wav".equals(au.loaded.get(7)),
                "sound: the 8 effects are loaded once (" + au.loaded + ")");
        check(au.musicOn && "music.ogg".equals(au.music) && au.musicLoop && au.musicStarts == 1, "sound: the music loop is playing");
        check(au.loopStarts == 1 && au.plays[7] == 1, "sound: the wind loop is playing");
        check(r.bbCalls > 100 && r.bbFlat > 100 && r.bbAdditive > 100, "effects: billboards, flat shadows and additive glows are drawn behind the menu");

        e.input.push(D, 0, 400, 450); e.input.push(U, 0, 400, 450);   // menu backend button
        t += 16666667L; e.frame(t, r); f++;
        check(req[1] == 1 && req[0] == BackendSwitcher.GLES2 && screen.isMenuShown(), "menu: the backend button asks for GLES 2");

        int clicks0 = au.plays[0];
        e.input.push(D, 0, 313, 387); e.input.push(U, 0, 313, 387);   // MUSIC off
        t += 16666667L; e.frame(t, r); f++;
        check(!au.musicOn && au.musicStops == 1 && au.plays[0] == clicks0 + 1, "menu: MUSIC off stops the music (and the button clicks)");
        e.input.push(D, 0, 313, 387); e.input.push(U, 0, 313, 387);   // MUSIC on
        t += 16666667L; e.frame(t, r); f++;
        check(au.musicOn && au.musicStarts == 2, "menu: MUSIC on starts it again");
        e.input.push(D, 0, 487, 387); e.input.push(U, 0, 487, 387);   // SFX off
        for (int k = 0; k < 3; k++) { t += 16666667L; e.frame(t, r); f++; }
        int clicksOff = au.plays[0];
        check(au.stopStreams == 1, "menu: SFX off stops the wind loop");
        for (int k = 0; k < 200; k++) { t += 16666667L; e.frame(t, r); f++; }
        int tot = 0; for (int i = 0; i < 8; i++) tot += au.plays[i];
        e.input.push(D, 0, 400, 309); e.input.push(U, 0, 400, 309);   // FREE ORBIT (silent click)
        t += 16666667L; e.frame(t, r); f++;
        int tot2 = 0; for (int i = 0; i < 8; i++) tot2 += au.plays[i];
        check(au.plays[0] == clicksOff && tot2 == tot, "menu: with SFX off no effect is started");
        Settings saved = new Settings(8);
        saved.load(e.getStorage(), "settings10.txt");
        check(!saved.getBoolean("sfxOn", true) && saved.getBoolean("musicOn", false), "menu: the SFX / MUSIC choice is saved");
        e.input.push(D, 0, 713, 38); e.input.push(U, 0, 713, 38);     // MENU
        t += 16666667L; e.frame(t, r); f++;
        e.input.push(D, 0, 487, 387); e.input.push(U, 0, 487, 387);   // SFX on again
        for (int k = 0; k < 40; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(au.loopStarts == 2, "menu: SFX on starts the wind loop again");

        e.input.push(D, 0, 400, 231); e.input.push(U, 0, 400, 231);   // START
        t += 16666667L; e.frame(t, r); f++;
        check(!screen.isMenuShown() && screen.isCutscene() && screen.getCutTick() <= 3, "START: hides the menu and plays the cutscene from tick 0 (tick " + screen.getCutTick() + ")");

        // the eye of every frame is exactly the pose of the current tick of the track
        int startFrame = f;
        float worst = 0f;
        boolean stepOk = true;
        int lastTick = screen.getCutTick();
        for (; f < startFrame + 600; f++) {
            t += 16666667L;
            e.frame(t, r);
            int tk = screen.getCutTick();
            stepOk &= tk == lastTick + 1;
            lastTick = tk;
            float dx = r.eyeX[r.clears - 1] - track.get(tk, CameraTrack.EYE_X);
            float dy = r.eyeY[r.clears - 1] - track.get(tk, CameraTrack.EYE_Y);
            float dz = r.eyeZ[r.clears - 1] - track.get(tk, CameraTrack.EYE_Z);
            worst = Math.max(worst, dx * dx + dy * dy + dz * dz);
        }
        check(stepOk, "cutscene: one tick of the track per logic tick");
        check(worst < 1e-3f, "cutscene: the eye of every frame is exactly a pose of the track (worst squared error " + worst + ")");

        int[][] script = {
            {10, D, 87, 442}, {11, U, 87, 442},               // FOG off
            {30, D, 87, 442}, {31, U, 87, 442},               // FOG on
            {50, D, 246, 442}, {51, U, 246, 442},             // SKY off
            {70, D, 246, 442}, {71, U, 246, 442},             // SKY on
            {90, D, 405, 442}, {91, U, 405, 442},             // LMAP off
            {110, D, 405, 442}, {111, U, 405, 442},           // LMAP on
            {130, D, 577, 442}, {131, U, 577, 442},           // HUD backend button
        };
        int fogOffBefore = r.fogOff, lm0 = r.lmapOff, lm1 = r.lmapOn, sky0 = r.skyDraws;
        int base = f;
        int si = 0;
        int skyAtSkyOff = -1, skyAtSkyOn = -1;
        for (int k = 0; k < 160; k++, f++) {
            while (si < script.length && script[si][0] == k) {
                e.input.push(script[si][1], 0, script[si][2], script[si][3]);
                si++;
            }
            t += 16666667L;
            e.frame(t, r);
            if (k == 60) skyAtSkyOff = r.skyDraws;
            if (k == 69) skyAtSkyOn = r.skyDraws;
        }
        check(skyAtSkyOff >= 0 && skyAtSkyOn == skyAtSkyOff, "HUD: SKY off stops the sky draw");
        check(r.skyDraws > skyAtSkyOn, "HUD: SKY on draws it again");
        check(r.lmapOff == lm0 + 1 && r.lmapOn == lm1 + 1, "HUD: LMAP off and on reach the renderer");
        check(req[1] == 2 && req[0] == BackendSwitcher.GLES2, "HUD: the backend button asks for GLES 2");
        check(r.fogOff > fogOffBefore, "HUD: FOG off reaches the renderer");

        // RESTART, CUT off (free orbit), CUT on
        for (int k = 0; k < 100; k++) { t += 16666667L; e.frame(t, r); f++; }
        int before = screen.getCutTick();
        e.input.push(D, 0, 246, 382); e.input.push(U, 0, 246, 382);   // RESTART
        t += 16666667L; e.frame(t, r); f++;
        check(screen.getCutTick() <= 3 && before > 50, "HUD: RESTART goes back to tick 0");
        for (int k = 0; k < 100; k++) { t += 16666667L; e.frame(t, r); f++; }
        e.input.push(D, 0, 87, 382); e.input.push(U, 0, 87, 382);     // CUT off
        t += 16666667L; e.frame(t, r); f++;
        int frozen = screen.getCutTick();
        for (int k = 0; k < 60; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(!screen.isCutscene() && screen.getCutTick() == frozen, "HUD: CUT off stops the track (free orbit)");
        e.input.push(D, 0, 400, 200); e.input.push(InputEvent.MOVE, 0, 500, 200); e.input.push(U, 0, 500, 200);   // drag turns the orbit
        t += 16666667L; e.frame(t, r); f++;
        e.input.push(D, 0, 87, 382); e.input.push(U, 0, 87, 382);     // CUT on
        t += 16666667L; e.frame(t, r); f++;
        for (int k = 0; k < 30; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(screen.isCutscene() && screen.getCutTick() > frozen, "HUD: CUT on goes on where the cutscene was");

        // the loop: the cutscene comes round after 2040 ticks; the effects and the sounds go with the scenes
        e.input.push(D, 0, 246, 382); e.input.push(U, 0, 246, 382);
        int loops0 = screen.getCutLoops();
        int[] p0 = au.plays.clone();
        int loopFrame0 = r.clears;
        float[] flashOf = new float[2100];
        int[] tickOf = new int[2100];
        for (int k = 0; k < 2100; k++) {
            t += 16666667L; e.frame(t, r); f++;
            flashOf[k] = screen.getFlash();
            tickOf[k] = screen.getCutTick();
        }
        check(screen.getCutLoops() == loops0 + 1, "cutscene: it loops after 34 s (loops " + screen.getCutLoops() + ")");
        int[] dp = new int[16];
        for (int i = 0; i < 16; i++) dp[i] = au.plays[i] - p0[i];
        check(dp[2] == 2, "sound: the ship horn sounds twice per loop, far in FLYOVER and near in SHIP (" + dp[2] + ")");
        check(dp[3] >= 13 && dp[3] <= 14, "sound: the lighthouse bell rings every half turn of the beam (" + dp[3] + " in 2100 ticks)");
        check(dp[4] == 4, "sound: welding crackle 4 times in QUAY (" + dp[4] + ")");
        check(dp[5] == 8 && dp[6] == 8, "sound: 8 launches and 8 booms in FINALE (" + dp[5] + ", " + dp[6] + ")");
        check(dp[1] == 4, "sound: a swoosh on each of the 4 hard cuts (" + dp[1] + ")");
        float fAt480 = -1f, fAt493 = -1f, fAt840 = -1f, fAtWrap = -1f;
        for (int k = 0; k < 2100; k++) {
            if (tickOf[k] == 480) fAt480 = flashOf[k];
            if (tickOf[k] == 493) fAt493 = flashOf[k];
            if (tickOf[k] == 840) fAt840 = flashOf[k];
            if (tickOf[k] == 0) fAtWrap = flashOf[k];
        }
        check(fAt480 > 0.3f && fAt493 == 0f && fAtWrap > 0.3f && fAt840 == 0f,
                "effects: a flash on the hard cuts, fading in 12 ticks, none at the cut-less PIER start (" + fAt480 + ", " + fAt493 + ", " + fAtWrap + ", " + fAt840 + ")");
        int finaleBefore = -1, finaleBurst = -1, minBb = 1 << 30;
        for (int k = 0; k < 2100; k++) {
            int fr = loopFrame0 + k;
            if (fr >= FRAMES) break;
            if (tickOf[k] == 1590) finaleBefore = r.bbFrame[fr];
            if (tickOf[k] == 1625) finaleBurst = r.bbFrame[fr];
            if (tickOf[k] > 300) minBb = Math.min(minBb, r.bbFrame[fr]);
        }
        check(finaleBurst >= finaleBefore + 15, "effects: a firework burst adds sparks in FINALE (" + finaleBefore + " -> " + finaleBurst + " billboards)");
        check(minBb >= 40, "effects: every frame has snow, halos, beam and lights (fewest billboards " + minBb + ")");

        // MENU button and FREE ORBIT
        e.input.push(D, 0, 713, 38); e.input.push(U, 0, 713, 38);     // MENU
        t += 16666667L; e.frame(t, r); f++;
        check(screen.isMenuShown() && screen.isCutscene(), "MENU: back to the menu over the cutscene");
        e.input.push(D, 0, 400, 309); e.input.push(U, 0, 400, 309);   // FREE ORBIT
        t += 16666667L; e.frame(t, r); f++;
        check(!screen.isMenuShown() && !screen.isCutscene(), "FREE ORBIT: hides the menu and stops the track");

        int maxBb = 0;
        for (int k = 0; k < Math.min(r.clears, FRAMES); k++) maxBb = Math.max(maxBb, r.bbFrame[k]);
        check(maxBb <= 500 && maxBb >= 300, "effects: the most billboards in one frame is " + maxBb + " (the batch holds 512)");

        // the FX button (HUD page, row 2) switches every billboard off and on
        e.input.push(D, 0, 405, 382); e.input.push(U, 0, 405, 382);   // FX off
        for (int k = 0; k < 20; k++) { t += 16666667L; e.frame(t, r); f++; }
        int fxOffBb = r.bbFrame[Math.min(r.clears, FRAMES) - 1];
        e.input.push(D, 0, 405, 382); e.input.push(U, 0, 405, 382);   // FX on
        for (int k = 0; k < 30; k++) { t += 16666667L; e.frame(t, r); f++; }
        int fxOnBb = r.bbFrame[Math.min(r.clears, FRAMES) - 1];
        check(fxOffBb == 0 && fxOnBb >= 25, "effects: the FX button switches the billboards off (" + fxOffBb + ") and on (" + fxOnBb + ")");

        // steady frames allocate (almost) nothing
        for (int k = 0; k < 300; k++) { t += 16666667L; e.frame(t, r); f++; }
        long a0 = allocated();
        for (int k = 0; k < 600; k++) { t += 16666667L; e.frame(t, r); f++; }
        long delta = allocated() - a0;
        check(delta < 20000, "port demo: 600 steady frames allocated " + delta + " bytes");


        // ---- physics show, quality levels, benchmark (HUD page: Q (87, 322), AUTO (246, 322), PHYS (405, 322), BENCH (713, 442))
        for (int k = 0; k < 400; k++) { t += 16666667L; e.frame(t, r); f++; }
        int bodies = screen.getBodyCount();
        check(bodies > 5 && bodies <= 80, "physics: balls and crates fall in the free orbit (" + bodies + " bodies, at most 80)");
        e.input.push(D, 0, 405, 322); e.input.push(U, 0, 405, 322);   // PHYS off
        for (int k = 0; k < 5; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(screen.getBodyCount() == 0, "physics: PHYS off removes every body");
        e.input.push(D, 0, 405, 322); e.input.push(U, 0, 405, 322);   // PHYS on
        for (int k = 0; k < 400; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(screen.getBodyCount() > 5, "physics: PHYS on starts the show again (" + screen.getBodyCount() + ")");

        check(screen.getQuality() == 2 && screen.isAutoQuality(), "quality: HIGH and AUTO at the start");
        e.input.push(D, 0, 87, 322); e.input.push(U, 0, 87, 322);     // Q: HIGH -> LOW
        for (int k = 0; k < 700; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(screen.getQuality() == 0 && !screen.isAutoQuality(), "quality: Q goes to LOW and stops AUTO");
        check(screen.getBodyCount() <= 24, "quality: LOW keeps at most 24 bodies (" + screen.getBodyCount() + ")");
        Settings qs = new Settings(8);
        qs.load(e.getStorage(), "bench10.txt");
        check(qs.getInt("quality", -1) == 0 && !qs.getBoolean("auto", true), "quality: the choice is saved");
        e.input.push(D, 0, 87, 322); e.input.push(U, 0, 87, 322);     // LOW -> MED
        e.input.push(D, 0, 87, 322); e.input.push(U, 0, 87, 322);     // MED -> HIGH
        for (int k = 0; k < 5; k++) { t += 16666667L; e.frame(t, r); f++; }
        check(screen.getQuality() == 2, "quality: Q cycles back to HIGH");

        e.input.push(D, 0, 713, 442); e.input.push(U, 0, 713, 442);   // BENCH
        t += 16666667L; e.frame(t, r); f++;
        check(screen.isBenchRunning() && screen.isCutscene() && screen.getCutTick() <= 3, "bench: BENCH starts the fixed run from tick 0 of the cutscene");
        e.input.push(D, 0, 87, 322); e.input.push(U, 0, 87, 322);     // Q during the run: disabled
        t += 16666667L; e.frame(t, r); f++;
        check(screen.getQuality() == 2, "bench: the other buttons are disabled during the run");
        int maxBodies = 0;
        long b0 = 0;
        for (int k = 0; k < 2200 && screen.isBenchRunning(); k++) {
            if (k == 900) b0 = allocated();
            t += 16666667L; e.frame(t, r); f++;
            if (k == 1500) {
                long d = allocated() - b0;
                check(d < 20000, "bench: 600 frames of the full show (physics, effects, sound) allocated " + d + " bytes");
            }
            maxBodies = Math.max(maxBodies, screen.getBodyCount());
        }
        check(!screen.isBenchRunning() && screen.getBenchScore() > 0, "bench: the run ends after one cutscene with a score (" + screen.getBenchScore() + ")");
        check(maxBodies >= 20 && maxBodies <= 80, "bench: the FINALE shower fills the platform (" + maxBodies + " bodies at most)");
        Settings bs = new Settings(8);
        bs.load(e.getStorage(), "bench10.txt");
        check(bs.getInt("best2", 0) == screen.getBenchScore() && screen.getBestScore() == screen.getBenchScore(), "bench: the best score of the level is saved in the Storage");

        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
