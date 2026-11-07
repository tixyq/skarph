// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.demo.v08.*;
import skarph.core.*;

/**
 * Headless test of the town demo screen (plain Java, needs no android.jar): loads the real map assets, runs 4500 frames
 * with scripted button taps, checks fog / sky / lightmap / backend requests, the cutscene (the camera follows the baked
 * track tick by tick, loops, RESTART and CUT buttons) and that a frame allocates (almost) nothing.
 */
public class TownDemoTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static final class R implements Renderer {
        int clears, meshDraws, skyDraws, fogOn, fogOff, lmapOn, lmapOff, quads, meshes, maxMeshDraws;
        int bbCalls, bbAdditive, bbFlat, bbRecords;
        final int[] bbFrame = new int[4500];
        final float[] eyeX = new float[4500], eyeY = new float[4500], eyeZ = new float[4500];
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
            if (clears > 0 && clears <= 4500) bbFrame[clears - 1] += n;
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
        public void clear(float r, float g, float b) { if (meshDraws > maxMeshDraws) maxMeshDraws = meshDraws; if (clears < 4500) { eyeX[clears] = Float.NaN; } clears++; meshDraws = 0; }
        public void setCamera(Mat4 p, Mat4 v) {
            // eye = -(R^T * t) of the column major view matrix; stored for the frame that is being drawn (clears - 1)
            int f = clears - 1;
            if (f >= 0 && f < 4500 && eyeX[f] != eyeX[f]) {           // the first camera of the frame (the HUD sets an identity view later)
                float[] m = v.m;
                eyeX[f] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
                eyeY[f] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
                eyeZ[f] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
            }
        }
        public void drawMesh(int m, Mat4 model) { meshDraws++; }
        public void drawQuads(int t, float[] v, int n) { quads += n; }
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** A fixed run at 60 FPS, then one at 30 FPS on the same engine (the second must not beat the record). */
    static void benchTest(String dir) {
        BackendSwitcher sw = new BackendSwitcher() {
            public boolean isGles2Available() { return false; }
            public void switchBackend(int b) { }
            public int getGcCount() { return 0; }
        };
        Engine e = new Engine();
        e.setAssets(new ContentTest.FileAssets(dir));
        StressDemoScreen screen = new StressDemoScreen(sw);
        R r = new R();
        e.setScreen(screen);
        int D = InputEvent.DOWN, U = InputEvent.UP;
        long t = 1000000000L;
        boolean midRun = false;
        int f = 0;
        for (; f < 3000; f++) {                          // run 1: 60 FPS, the button is tapped at frame 10
            t += 16666667L;
            if (f == 10) e.input.push(D, 0, 713, 442);
            if (f == 11) e.input.push(U, 0, 713, 442);
            if (f == 500) midRun = screen.isBenchRunning();
            e.frame(t, r);
            if (f > 100 && !screen.isBenchRunning()) break;
        }
        int score1 = screen.getBenchScore();
        check(midRun && !screen.isBenchRunning(), "benchmark: runs while the cutscene plays once and stops by itself (" + f + " frames)");
        check(f > 1900 && f < 1960, "benchmark: one run is one cutscene = 1920 ticks = about 1920 frames at 60 FPS (" + f + ")");
        check(score1 > 5900 && score1 < 6100, "benchmark: a steady 60 FPS scores about 6000 (" + score1 + ")");
        check(screen.getBestScore() == score1 && e.getStorage().load("bench08.txt") != null, "benchmark: the record is saved in the Storage");
        int g = 0;
        e.input.push(D, 0, 713, 442);
        e.input.push(U, 0, 713, 442);
        for (; g < 3000; g++) {                          // run 2: 30 FPS (two ticks per frame)
            t += 33333333L;
            e.frame(t, r);
            if (g > 100 && !screen.isBenchRunning()) break;
        }
        int score2 = screen.getBenchScore();
        check(g > 900 && g < 1000, "benchmark: the same run takes half the frames at 30 FPS (" + g + ")");
        check(score2 > 2900 && score2 < 3100, "benchmark: 30 FPS scores about 3000 (" + score2 + ")");
        check(screen.getBestScore() == score1, "benchmark: a worse run does not replace the record (" + screen.getBestScore() + ")");
        // a new screen on the same storage reads the record back
        StressDemoScreen again = new StressDemoScreen(sw);
        e.setScreen(again);
        t += 16666667L;
        e.frame(t, r);
        check(again.getBestScore() == score1, "benchmark: the record is read back after a restart");
    }

    /** A slow device drops to LOW by itself; the buttons QUAL and AUTO; the show shrinks with the level. */
    static void qualityTest(String dir) {
        BackendSwitcher sw = new BackendSwitcher() {
            public boolean isGles2Available() { return false; }
            public void switchBackend(int b) { }
            public int getGcCount() { return 0; }
        };
        Engine e = new Engine();
        e.setAssets(new ContentTest.FileAssets(dir));
        StressDemoScreen screen = new StressDemoScreen(sw);
        R r = new R();
        e.setScreen(screen);
        long t = 1000000000L;
        int lvl = 2, f = 0, firstDrop = -1, lowAt = -1, maxBodiesLow = 0, changes = 0;
        for (; f < 1200; f++) {                                  // 20 FPS: three ticks per frame (catch up)
            t += 50000000L;
            e.frame(t, r);
            if (screen.getQuality() != lvl) {
                lvl = screen.getQuality();
                changes++;
                if (firstDrop < 0) firstDrop = f;
                if (lvl == 0 && lowAt < 0) lowAt = f;
            }
            if (lowAt >= 0 && f > lowAt) maxBodiesLow = Math.max(maxBodiesLow, screen.getShowBodies());
        }
        check(firstDrop > 0 && firstDrop < 140, "quality: 20 FPS lowers the level after the warm up and 3 s (frame " + firstDrop + ")");
        check(screen.getQuality() == 0 && changes == 2, "quality: it goes down to LOW and stops there (level " + screen.getQuality() + ", " + changes + " changes)");
        check(maxBodiesLow <= 24, "quality: LOW keeps at most 24 physics bodies (" + maxBodiesLow + ")");
        for (int i = 0; i < 60 * 30; i++) {                      // 30 s of 60 FPS: no climbing back to the level that was too slow
            t += 16666667L;
            e.frame(t, r);
        }
        check(screen.getQuality() == 0, "quality: a fast period does not raise the level again");
        int D = InputEvent.DOWN, U = InputEvent.UP;
        e.input.push(D, 0, 87, 322); e.input.push(U, 0, 87, 322);    // QUAL: manual, goes to MED and switches AUTO off
        t += 16666667L; e.frame(t, r);
        check(screen.getQuality() == 1 && !screen.isAutoQuality(), "quality: the QUAL button picks the next level and stops AUTO (level " + screen.getQuality() + ")");
        for (int i = 0; i < 60 * 30; i++) {
            t += 16666667L;
            e.frame(t, r);
        }
        check(screen.getQuality() == 1, "quality: with AUTO off the level stays as picked");
        e.input.push(D, 0, 246, 322); e.input.push(U, 0, 246, 322);  // AUTO on again
        t += 16666667L; e.frame(t, r);
        check(screen.isAutoQuality(), "quality: the AUTO button switches it on again");
        // the choice is saved and read back by a new screen
        StressDemoScreen again = new StressDemoScreen(sw);
        e.setScreen(again);
        t += 16666667L; e.frame(t, r);
        check(again.getQuality() == 1 && again.isAutoQuality(), "quality: the level and AUTO are read back after a restart (level " + again.getQuality() + ")");
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "demos/demo-v0.8/assets";
        final int[] req = new int[2];
        BackendSwitcher sw = new BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int b) { req[0] = b; req[1]++; }
            public int getGcCount() { return 3; }
        };
        Engine e = new Engine();
        e.setAssets(new ContentTest.FileAssets(dir));
        StressDemoScreen screen = new StressDemoScreen(sw);
        R r = new R();
        e.setScreen(screen);
        int D = InputEvent.DOWN, U = InputEvent.UP;
        // buttons at y 442 px: FOG 87, SKY 246, LMAP 405, BACKEND 577; second row at y 382 px: CUT 87, RESTART 246, FX 405
        int[][] script = {
            {300, D, 87, 442}, {301, U, 87, 442},            // FOG off
            {400, D, 87, 442}, {401, U, 87, 442},            // FOG on
            {500, D, 246, 442}, {501, U, 246, 442},          // SKY off
            {600, D, 246, 442}, {601, U, 246, 442},          // SKY on
            {700, D, 405, 442}, {701, U, 405, 442},          // LMAP off
            {800, D, 405, 442}, {801, U, 405, 442},          // LMAP on
            {900, D, 577, 442}, {901, U, 577, 442},          // backend switch
            {1000, D, 400, 200}, {1001, InputEvent.MOVE, 500, 200}, {1002, U, 500, 200},  // drag (cutscene on: no effect)
            {1100, D, 246, 382}, {1101, U, 246, 382},        // RESTART: the cutscene starts again at tick 0
            {1500, D, 87, 382}, {1501, U, 87, 382},          // CUT off: free orbit
            {1600, D, 87, 382}, {1601, U, 87, 382},          // CUT on: the cutscene goes on where it was
            {1700, D, 405, 382}, {1701, U, 405, 382},        // FX off: no billboards at all
            {1800, D, 405, 382}, {1801, U, 405, 382},        // FX on
            {2000, D, 577, 382}, {2001, U, 577, 382},        // PHYS off: the show is removed
            {2100, D, 577, 382}, {2101, U, 577, 382}};       // PHYS on
        long t = 1000000000L;
        int fogOffAtFrame = -1, skyBefore = 0, skyAfter = 0, drawnMax = 0;
        long a0 = 0;
        int steadyFrames = 0;
        int[] bodies = new int[4500];
        for (int f = 0; f < 4500; f++) {
            t += 16666667L;
            for (int[] s : script) if (s[0] == f) e.input.push(s[1], 0, s[2], s[3]);
            if (f == 520) skyBefore = r.skyDraws;
            if (f == 550) skyAfter = r.skyDraws;
            if (f == 350) { fogOffAtFrame = r.fogNow ? 0 : 1; }
            e.frame(t, r);
            bodies[f] = screen.getShowBodies();
            if (f == 3200) a0 = allocated();
            if (f > 3200) steadyFrames++;
        }
        long delta = allocated() - a0;
        check(r.clears == 4500, "town demo: a frame is rendered 4500 times");
        check(r.meshes >= 8 && r.skyDraws > 3000, "town demo: all models and the sky mesh were created, the sky is drawn (" + r.skyDraws + " draws)");
        check(fogOffAtFrame == 1, "town demo: the FOG button turns the fog off");
        check(r.fogOn > 3000 && r.fogOff > 1000 && r.lastFogEnd == 170f, "town demo: fog is set on and off every frame (" + r.fogOn + " on, " + r.fogOff + " off)");
        check(skyAfter == skyBefore, "town demo: the SKY button stops drawing the sky (" + (skyAfter - skyBefore) + " draws in 30 frames while it is off)");
        check(r.lmapOff == 1 && r.lmapOn == 1, "town demo: the LMAP button switches the lightmaps off and on");
        check(req[1] == 1 && req[0] == BackendSwitcher.GLES2, "town demo: the backend button asks for ES 2.0");
        check(r.maxMeshDraws > 100, "town demo: the town and its props are drawn (" + r.maxMeshDraws + " meshes in the busiest frame)");
        // ---- cutscene: the eye of every frame must be the pose of one tick of the baked track
        CameraTrack track = null;
        Timeline tl = null;
        try {
            track = CameraTrack.parse(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "cutscene.cam")));
            tl = Timeline.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "cutscene.tl")), "UTF-8"));
        } catch (Exception ex) {
            System.out.println("cannot read the cutscene files: " + ex);
        }
        check(track != null && tl != null && tl.getSceneCount() == 5 && tl.getEndTick() == 1920 && track.getTickCount() >= tl.getEndTick(),
                "cutscene assets: 5 scenes, 32 s, the track covers the timeline");
        int[] tick = new int[4500];
        float worst = 0f;
        for (int f = 0; f < 4500; f++) {
            int best = 0;
            float bd = 1e30f;
            for (int k = 0; k < track.getTickCount(); k++) {          // nearest pose (poses are distinct)
                float dx = r.eyeX[f] - track.get(k, CameraTrack.EYE_X);
                float dy = r.eyeY[f] - track.get(k, CameraTrack.EYE_Y);
                float dz = r.eyeZ[f] - track.get(k, CameraTrack.EYE_Z);
                float d = dx * dx + dy * dy + dz * dz;
                if (d < bd) { bd = d; best = k; }
            }
            tick[f] = best;
            if (f < 1500 || f >= 1700) worst = Math.max(worst, bd);
        }
        check(worst < 1e-3f, "cutscene: while it runs, the eye of every frame is exactly a pose of the track (worst squared error " + worst + ")");
        boolean stepOk = true;
        int loops = 0;
        for (int f = 120; f < 1090; f++) {
            if (tick[f + 1] != tick[f] + 1) stepOk = false;
        }
        for (int f = 1120; f < 1490; f++) {
            if (tick[f + 1] != tick[f] + 1) stepOk = false;
        }
        for (int f = 1710; f < 4499; f++) {
            if (tick[f + 1] == 0 && tick[f] == 1919) loops++;
            else if (tick[f + 1] != tick[f] + 1) stepOk = false;
        }
        check(stepOk, "cutscene: one tick of the track per logic tick (no skipped or repeated pose)");
        check(loops == 1, "cutscene: the timeline loops at its end (tick 1919 -> 0, " + loops + " loop in the run)");
        check(tick[1110] < 20, "cutscene: RESTART jumps back to the start (tick " + tick[1110] + " ten frames after the tap)");
        float orbitR = (float) Math.sqrt(r.eyeX[1550] * r.eyeX[1550] + r.eyeZ[1550] * r.eyeZ[1550]);
        check(Math.abs(orbitR - 50f) < 0.01f && Math.abs(r.eyeY[1550] - 27f) < 0.01f, "cutscene: CUT off gives the free orbit (radius 50, height 27, got " + orbitR + ")");
        check(tick[1610] - tick[1495] >= 10 && tick[1610] - tick[1495] <= 20,
                "cutscene: CUT on goes on where it was left (tick " + tick[1495] + " before, " + tick[1610] + " after)");
        int fxOffFrames = 0, fxOnFrames = 0;
        for (int f = 1710; f < 1790; f++) fxOffFrames += r.bbFrame[f];
        for (int f = 1820; f < 1900; f++) if (r.bbFrame[f] >= 16) fxOnFrames++;
        check(fxOffFrames == 0, "effects: the FX button switches every billboard off (" + fxOffFrames + " drawn in 80 frames)");
        check(fxOnFrames == 80, "effects: with FX on every frame draws shadows, flames, orbs and particles (>= 16 billboards in " + fxOnFrames + "/80 frames)");
        check(r.bbFlat > 1000 && r.bbAdditive > 1000 && r.bbCalls > 4000, "effects: flat shadow billboards (" + r.bbFlat + "), additive batches (" + r.bbAdditive + "), " + r.bbCalls + " billboard draw calls in the run");
        check(r.bbRecords / (double) r.bbCalls > 3.0, "effects: the batches are big (" + (r.bbRecords / (double) r.bbCalls) + " billboards per draw call)");
        int maxBodies = 0, offMax = 0, afterLoop = 99;
        for (int f = 0; f < 4500; f++) maxBodies = Math.max(maxBodies, bodies[f]);
        for (int f = 2010; f < 2100; f++) offMax = Math.max(offMax, bodies[f]);
        for (int f = 1711; f < 4499; f++) if (tick[f + 1] == 0 && tick[f] == 1919 && f > 2200) afterLoop = bodies[f + 1];
        check(maxBodies >= 20 && maxBodies <= 80, "physics show: balls and crates rain down (" + maxBodies + " bodies at most, pool " + 80 + ")");
        check(offMax == 0, "physics show: the PHYS button removes every body (" + offMax + " left)");
        check(bodies[2300] > 0, "physics show: PHYS on starts it again (" + bodies[2300] + " bodies)");
        check(afterLoop <= 1, "physics show: the loop of the cutscene restarts the show (" + afterLoop + " bodies right after tick 0)");
        check(delta < 20000, "town demo: " + steadyFrames + " steady frames allocated " + delta + " bytes");
        benchTest(dir);
        qualityTest(dir);
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
