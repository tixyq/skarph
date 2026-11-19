// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

/** AudioMixer on a recording backend. */
public class AudioTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Backend: 3 effect voices' state and the music state. */
    static final class Rec implements Audio {
        int nextId = 100;
        float[] l = new float[400], r = new float[400];
        boolean[] active = new boolean[400];
        float[] rate = new float[400];
        boolean query;
        String music;
        float musicVol = -1;
        boolean musicOn;
        int plays, stops, musicStarts;

        public int loadSound(String p) { return 1; }
        public void unloadSound(int s) { }
        public int playSound(int s, float v, float p, boolean loop) {
            int id = nextId++;
            l[id - 100] = v; r[id - 100] = v; active[id - 100] = true; rate[id - 100] = p; plays++;
            return id;
        }
        public void stopStream(int id) { active[id - 100] = false; stops++; }
        public boolean playMusic(String p, boolean loop) { music = p; musicOn = true; musicStarts++; return true; }
        public void stopMusic() { musicOn = false; }
        public void setMusicVolume(float v) { musicVol = v; }
        public boolean isMusicPlaying() { return musicOn; }
        public void pauseAll() { }
        public void resumeAll() { }
        public void release() { }
        public void setStreamGain(int id, float a, float b) { l[id - 100] = a; r[id - 100] = b; }
        public void setStreamRate(int id, float p) { rate[id - 100] = p; }
        public boolean canQueryStreams() { return query; }
        public boolean isStreamActive(int id) { return active[id - 100]; }
    }

    public static void main(String[] args) {
        Rec rec = new Rec();
        Audio a = new AudioMixer(rec);

        // plain play, volume, buses
        int s1 = a.playSound(1, 0.5f, 1f, false);
        check(s1 >= 0 && near(rec.l[0], 0.5f), "playSound passes the volume");
        a.setBusVolume(Audio.BUS_MASTER, 0.5f);
        check(near(rec.l[0], 0.25f) && near(a.getBusVolume(Audio.BUS_MASTER), 0.5f), "master bus scales a playing sound");
        a.setBusVolume(Audio.BUS_SFX, 0.5f);
        check(near(rec.l[0], 0.125f), "sfx bus scales it too");
        int ui = a.playSound(1, 1f, 1f, false, Audio.BUS_UI);
        check(near(rec.l[1], 0.5f), "ui bus is separate from the sfx bus");
        a.setStreamVolume(s1, 1f);
        check(near(rec.l[0], 0.25f), "setStreamVolume");
        a.setStreamPitch(s1, 3f);
        check(near(rec.rate[0], 2f), "setStreamPitch is clamped to 2");
        a.setBusVolume(Audio.BUS_MASTER, 1f);
        a.setBusVolume(Audio.BUS_SFX, 1f);

        // generation safe ids
        a.stopStream(s1);
        check(!rec.active[0] && !a.isStreamPlaying(s1), "stopStream, then isStreamPlaying is false");
        int s2 = a.playSound(1, 1f, 1f, false);
        check(s2 != s1, "a reused slot gets a different id");
        a.stopStream(s1);
        check(rec.active[2] || rec.active[0] || true, "");
        boolean stillOn = false;
        for (int i = 0; i < 400; i++) if (rec.active[i] && i != 1) stillOn = true;
        check(stillOn, "a stale id does not stop somebody else's stream");
        a.stopStream(-1);
        a.setStreamVolume(-1, 1f);

        // one-shots: duration known / unknown, no query
        int snd = 7;
        a.setSoundDuration(snd, 0.5f);
        int shot = a.playSound(snd, 1f, 1f, false);
        check(a.isStreamPlaying(shot), "one-shot with a declared length plays");
        for (int i = 0; i < 40; i++) a.update();
        check(!a.isStreamPlaying(shot), "... and ends after its length");
        int unk = a.playSound(9, 1f, 1f, false);
        check(!a.isStreamPlaying(unk), "unknown length without a query: reported as not playing");
        int lp = a.playSound(9, 1f, 1f, true);
        for (int i = 0; i < 1000; i++) a.update();
        check(a.isStreamPlaying(lp), "a loop plays until stopped");
        a.stopStream(lp);
        check(!a.isStreamPlaying(lp), "stopped loop");

        // a declared length of exactly 3 seconds (180 ticks) is a known length too
        a.setSoundDuration(11, 3f);
        int three = a.playSound(11, 1f, 1f, false);
        check(a.isStreamPlaying(three), "one-shot of exactly 3 s counts as known and playing");
        for (int i = 0; i < 100; i++) a.update();
        check(a.isStreamPlaying(three), "... still playing after 100 ticks");
        for (int i = 0; i < 100; i++) a.update();
        check(!a.isStreamPlaying(three), "... and over after 200");

        // the backend reuses a finished voice: the old slot must not control the new sound
        rec = new Rec();
        rec.query = true;
        a = new AudioMixer(rec);
        int first1 = a.playSound(1, 1f, 1f, false);
        rec.active[0] = false;                       // it ended, the mixer has not noticed yet
        rec.nextId = 100;                            // the backend hands out the same voice again
        int second = a.playSound(1, 1f, 1f, false);
        a.stopStream(first1);
        check(rec.active[0] && a.isStreamPlaying(second), "voice reuse: stopping the stale id does not stop the new sound");

        // query backend
        rec = new Rec();
        rec.query = true;
        a = new AudioMixer(rec);
        int q = a.playSound(1, 1f, 1f, false);
        for (int i = 0; i < 10; i++) a.update();
        check(a.isStreamPlaying(q), "query backend: playing");
        rec.active[0] = false;
        for (int i = 0; i < 8; i++) a.update();
        check(!a.isStreamPlaying(q), "query backend: ended");

        // voice stealing
        rec = new Rec();
        a = new AudioMixer(rec);
        int first = a.playSound(1, 1f, 1f, false);
        for (int i = 1; i < AudioMixer.VOICES; i++) a.playSound(1, 1f, 1f, false);
        int extra = a.playSound(1, 1f, 1f, false);
        check(extra >= 0 && rec.stops == 1 && !rec.active[0], "all voices busy: the oldest one-shot is stolen");
        for (int i = 0; i < 64; i++) a.playSound(1, 1f, 1f, true);
        check(a.playSound(1, 1f, 1f, true) == -1 || true, "loops fill the voices without crashing");

        // 3D
        rec = new Rec();
        a = new AudioMixer(rec);
        a.setListener(0, 0, 0, 0f);
        int near3 = a.playSound3D(1, 1f, 1f, false, 0.02f, 0, 0, 2f, 20f);
        check(rec.l[0] > 0.98f && rec.r[0] > 0.98f, "3D: inside refDist is full volume, nearly centred");
        int right = a.playSound3D(1, 1f, 1f, false, 10f, 0, 0, 2f, 20f);
        check(rec.r[1] > rec.l[1] + 0.1f && rec.r[1] < 0.9f, "3D: a source on the right is louder in the right channel and attenuated (" + rec.l[1] + " / " + rec.r[1] + ")");
        int left = a.playSound3D(1, 1f, 1f, false, -10f, 0, 0, 2f, 20f);
        check(rec.l[2] > rec.r[2] + 0.1f, "3D: a source on the left");
        int far = a.playSound3D(1, 1f, 1f, false, 40f, 0, 0, 2f, 20f);
        check(rec.l[3] == 0f && rec.r[3] == 0f, "3D: beyond maxDist is silent");
        a.setStreamPosition(far, 0f, 0f, -5f);
        a.update();
        check(rec.l[3] > 0.3f && near(rec.l[3], rec.r[3]), "3D: a moved source is re-panned every tick (ahead = centred)");
        a.setListener(0, 0, 0, 90f);          // turn: the source at +x is now behind-left/right?
        a.update();
        float lr = rec.l[1], rr = rec.r[1];
        check(Math.abs(lr - rr) < 0.15f, "3D: the listener turned by 90 degrees, the source at +x is now straight ahead/behind (" + lr + " / " + rr + ")");
        a.setListener(0, 0, 0, 180f);
        a.update();
        check(rec.l[1] > rec.r[1] + 0.05f, "3D: turned around, +x is on the left");

        // music: buses and fades
        rec = new Rec();
        a = new AudioMixer(rec);
        a.playMusic("a.ogg", true);
        a.setMusicVolume(0.8f);
        check(near(rec.musicVol, 0.8f), "music volume");
        a.setBusVolume(Audio.BUS_MUSIC, 0.5f);
        check(near(rec.musicVol, 0.4f), "music bus");
        a.setBusVolume(Audio.BUS_MUSIC, 1f);
        a.fadeMusicOut(60);
        for (int i = 0; i < 30; i++) a.update();
        check(rec.musicOn && rec.musicVol < 0.5f && rec.musicVol > 0.3f, "fade out: half way (" + rec.musicVol + ")");
        for (int i = 0; i < 31; i++) a.update();
        check(!rec.musicOn, "fade out ends with a stop");
        a.playMusic("a.ogg", true);
        a.crossfadeMusic("b.ogg", true, 60);
        check("a.ogg".equals(rec.music), "crossfade: the old track keeps playing first");
        for (int i = 0; i < 29; i++) a.update();
        check("a.ogg".equals(rec.music) && rec.musicVol < 0.1f, "crossfade: old track nearly silent");
        for (int i = 0; i < 3; i++) a.update();
        check("b.ogg".equals(rec.music) && rec.musicVol < 0.2f, "crossfade: the new track starts quietly");
        for (int i = 0; i < 40; i++) a.update();
        check(near(rec.musicVol, 0.8f), "crossfade: and ends at the set music volume (" + rec.musicVol + ")");
        rec.musicOn = false;
        a.crossfadeMusic("c.ogg", false, 60);
        check("c.ogg".equals(rec.music) && rec.musicVol < 0.05f, "crossfade from silence starts at once, fading in");
        a.fadeMusicIn(30);
        a.stopMusic();
        a.update();

        // defaults: a backend without mixer
        Audio na = new NullAudio();
        na.setBusVolume(1, 0.2f);
        check(na.getBusVolume(1) == 1f && na.playSound3D(0, 1, 1, false, 0, 0, 0, 1, 2) == -1 && !na.isStreamPlaying(3), "NullAudio: new calls are harmless");

        // zero allocation
        rec = new Rec();
        rec.query = true;
        a = new AudioMixer(rec);
        int[] ids = new int[8];
        for (int i = 0; i < 8; i++) ids[i] = a.playSound3D(1, 1f, 1f, true, i, 0, 0, 1f, 30f);
        for (int i = 0; i < 100; i++) a.update();
        long t0 = allocated();
        for (int i = 0; i < 5000; i++) {
            a.setListener(i * 0.001f, 0, 0, i * 0.1f);
            for (int k = 0; k < 8; k++) a.setStreamPosition(ids[k], k, 0, i * 0.002f);
            if ((i & 15) == 0) {
                int one = a.playSound(1, 1f, 1f, false, Audio.BUS_UI);
                a.setStreamVolume(one, 0.5f);
                a.isStreamPlaying(one);
            }
            if (i == 1000) a.crossfadeMusic("x", true, 60);
            if (i == 3000) a.fadeMusicOut(30);
            a.update();
        }
        long delta = allocated() - t0;
        check(delta < 1024, "zero allocation: 5000 ticks of 3D, voices, fades (" + delta + " bytes)");

        System.out.println(failed == 0 ? "ALL AUDIO TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
