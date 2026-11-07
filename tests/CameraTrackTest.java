// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;
import java.io.*;

/** Reads a camera track baked by tools/make_camera_track.py (path in args[0]) and checks it (JVM). */
public class CameraTrackTest {
    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        byte[] data = new byte[(int) f.length()];
        DataInputStream in = new DataInputStream(new FileInputStream(f));
        in.readFully(data);
        in.close();
        CameraTrack t = CameraTrack.parse(data);
        boolean ok = t.getTickCount() == 361
                && t.get(0, CameraTrack.EYE_X) == 10f && t.getFov(0) == 60f                 // first key
                && t.get(120, CameraTrack.EYE_Z) == 14f && t.getFov(120) == 50f             // key at 2.0 s
                && t.get(239, CameraTrack.EYE_Y) > 3.9f && t.get(239, CameraTrack.EYE_Y) < 4.1f
                && t.get(240, CameraTrack.EYE_Y) == 20f && t.getFov(240) == 70f             // hard cut at 4.0 s: one tick
                && t.get(360, CameraTrack.EYE_Y) == 12f && t.getFov(360) == 40f;            // last key
        System.out.println((ok ? "PASS " : "FAIL ") + "camera track baked by the python tool (" + t.getTickCount() + " ticks)");
        System.exit(ok ? 0 : 1);
    }
}
