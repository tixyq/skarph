// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A pre-recorded camera path for cutscenes: exactly ONE pose per tick (60 per second), no interpolation at run time
 * (the same rule as frame animation). A hard cut is simply two neighbouring poses far apart. The smooth motion between
 * poses is baked by tools/make_camera_track.py, so the engine only looks up the pose for the current tick.
 *
 * File format "CAM1" (little endian):
 * <pre>
 *   4 bytes  magic 'C','A','M','1'
 *   int32    tickCount (1..MAX_TICKS)
 *   float[tickCount*7]   per tick: eyeX, eyeY, eyeZ, targetX, targetY, targetZ, fovYDegrees
 * </pre>
 * The up vector is always (0, 1, 0): no roll. The eye must differ from the target, and the camera must not look
 * straight up or down (horizontal distance eye-target must be above 0.0001); fov is 1..170 degrees.
 *
 * parse()/toBytes() are load-time code (allocate). get()/apply() allocate nothing.
 */
public final class CameraTrack {
    public static final int FLOATS = 7;
    public static final int EYE_X = 0;
    public static final int EYE_Y = 1;
    public static final int EYE_Z = 2;
    public static final int TARGET_X = 3;
    public static final int TARGET_Y = 4;
    public static final int TARGET_Z = 5;
    public static final int FOV = 6;
    /** One hour at 60 ticks per second. */
    public static final int MAX_TICKS = 216000;

    private final float[] poses;
    private final int count;

    /**
     * @param poses count * 7 floats (copied)
     * @throws IllegalArgumentException for an empty track, too many ticks or an invalid pose
     */
    public CameraTrack(float[] poses, int count) {
        if (count < 1 || count > MAX_TICKS) {
            throw new IllegalArgumentException("bad tick count " + count);
        }
        if (poses == null || poses.length < count * FLOATS) {
            throw new IllegalArgumentException("pose array is too short");
        }
        this.count = count;
        this.poses = new float[count * FLOATS];
        System.arraycopy(poses, 0, this.poses, 0, count * FLOATS);
        for (int t = 0; t < count; t++) {
            validate(this.poses, t);
        }
    }

    private static void validate(float[] p, int tick) {
        int o = tick * FLOATS;
        for (int i = 0; i < FLOATS; i++) {
            float v = p[o + i];
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("tick " + tick + ": value " + i + " is not a finite number");
            }
        }
        float fov = p[o + FOV];
        if (fov < 1f || fov > 170f) {
            throw new IllegalArgumentException("tick " + tick + ": fov " + fov + " out of range 1..170");
        }
        float dx = p[o + TARGET_X] - p[o + EYE_X];
        float dz = p[o + TARGET_Z] - p[o + EYE_Z];
        if (dx * dx + dz * dz < 1e-8f) {
            throw new IllegalArgumentException("tick " + tick
                    + ": the camera looks straight up/down or eye == target");
        }
    }

    /** @throws IllegalArgumentException if the data is not a valid camera track */
    public static CameraTrack parse(byte[] data) {
        if (data == null || data.length < 8) {
            throw new IllegalArgumentException("camera track is too short");
        }
        if (data[0] != 'C' || data[1] != 'A' || data[2] != 'M' || data[3] != '1') {
            throw new IllegalArgumentException("not a camera track (bad magic)");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(4);
        int n = bb.getInt();
        if (n < 1 || n > MAX_TICKS) {
            throw new IllegalArgumentException("bad tick count " + n);
        }
        if ((long) bb.remaining() < (long) n * FLOATS * 4) {
            throw new IllegalArgumentException("camera track is truncated");
        }
        float[] p = new float[n * FLOATS];
        bb.asFloatBuffer().get(p);
        return new CameraTrack(p, n);
    }

    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(8 + count * FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'C').put((byte) 'A').put((byte) 'M').put((byte) '1');
        bb.putInt(count);
        bb.asFloatBuffer().put(poses);
        return bb.array();
    }

    public int getTickCount() {
        return count;
    }

    /** true when the tick is past the last pose (the camera then stays on the last pose). */
    public boolean isFinished(int tick) {
        return tick >= count;
    }

    /** One value of the pose of the tick (EYE_X .. FOV). A tick out of range is clamped to the first/last pose. */
    public float get(int tick, int field) {
        return poses[clamp(tick) * FLOATS + field];
    }

    /** Puts the camera on the pose of the tick (view matrix and frustum). The projection is not touched, see getFov(). */
    public void apply(int tick, Camera cam) {
        int o = clamp(tick) * FLOATS;
        float[] p = poses;
        cam.lookAt(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], 0f, 1f, 0f);
    }

    public float getFov(int tick) {
        return poses[clamp(tick) * FLOATS + FOV];
    }

    private int clamp(int tick) {
        if (tick < 0) {
            return 0;
        }
        return tick >= count ? count - 1 : tick;
    }
}
