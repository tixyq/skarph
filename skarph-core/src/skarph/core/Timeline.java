// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * A cutscene timeline: a list of scenes, each starting at a tick, all inside one tick range 0..endTick. The game keeps
 * one tick counter (the same one that drives the CameraTrack) and asks the timeline which scene is running. Everything
 * is in ticks (60 per second), nothing is interpolated.
 *
 * Text format (load time, see parse()):
 * <pre>
 *   # comment
 *   0    FLYOVER        start second and a name (1..16 characters, no spaces)
 *   8    GATE
 *   13.5 PLAZA
 *   end 32              the end second, always the last line
 * </pre>
 * Seconds are rounded to ticks. The first scene must start at 0, starts must grow, at most MAX_SCENES scenes.
 *
 * parse() and the constructor allocate (load time). All other methods allocate nothing.
 */
public final class Timeline {
    public static final int TPS = 60;
    public static final int MAX_SCENES = 64;
    public static final int MAX_NAME = 16;

    private final int[] starts;
    private final String[] names;
    private final int count;
    private final int endTick;

    /**
     * @param starts first tick of every scene, strictly growing, starts[0] == 0 (copied)
     * @param names  one name per scene, 1..MAX_NAME characters (copied)
     * @throws IllegalArgumentException for bad values
     */
    public Timeline(int[] starts, String[] names, int count, int endTick) {
        if (count < 1 || count > MAX_SCENES) {
            throw new IllegalArgumentException("bad scene count " + count);
        }
        if (starts == null || names == null || starts.length < count || names.length < count) {
            throw new IllegalArgumentException("scene arrays are too short");
        }
        if (starts[0] != 0) {
            throw new IllegalArgumentException("the first scene must start at tick 0");
        }
        this.starts = new int[count];
        this.names = new String[count];
        for (int i = 0; i < count; i++) {
            if (i > 0 && starts[i] <= starts[i - 1]) {
                throw new IllegalArgumentException("scene " + i + ": start must be after the previous scene");
            }
            String n = names[i];
            if (n == null || n.length() < 1 || n.length() > MAX_NAME) {
                throw new IllegalArgumentException("scene " + i + ": the name must have 1.." + MAX_NAME + " characters");
            }
            this.starts[i] = starts[i];
            this.names[i] = n;
        }
        if (endTick <= starts[count - 1]) {
            throw new IllegalArgumentException("the end must be after the start of the last scene");
        }
        this.count = count;
        this.endTick = endTick;
    }

    /** @throws IllegalArgumentException with the line number if the text is not a valid timeline */
    public static Timeline parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("timeline text is null");
        }
        int[] st = new int[MAX_SCENES];
        String[] nm = new String[MAX_SCENES];
        int n = 0;
        int end = -1;
        String[] lines = text.split("\n");
        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            if (end >= 0) {
                throw new IllegalArgumentException("line " + (ln + 1) + ": nothing may follow the end line");
            }
            String[] t = line.split("\\s+");
            if (t.length != 2) {
                throw new IllegalArgumentException("line " + (ln + 1) + ": expected '<seconds> <name>' or 'end <seconds>'");
            }
            if (t[0].equals("end")) {
                end = toTick(t[1], ln + 1);
            } else {
                if (n >= MAX_SCENES) {
                    throw new IllegalArgumentException("line " + (ln + 1) + ": too many scenes (max " + MAX_SCENES + ")");
                }
                st[n] = toTick(t[0], ln + 1);
                nm[n] = t[1];
                n++;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("the 'end <seconds>' line is missing");
        }
        try {
            return new Timeline(st, nm, n, end);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static int toTick(String s, int line) {
        float v;
        try {
            v = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + line + ": '" + s + "' is not a number");
        }
        if (v != v || v < 0f || v > 3600f) {
            throw new IllegalArgumentException("line " + line + ": seconds must be 0..3600");
        }
        return (int) (v * TPS + 0.5f);
    }

    public int getSceneCount() {
        return count;
    }

    /** First tick of the scene. */
    public int getStart(int scene) {
        return starts[scene];
    }

    /** First tick AFTER the scene (the start of the next scene, or the end of the timeline). */
    public int getEnd(int scene) {
        return scene + 1 < count ? starts[scene + 1] : endTick;
    }

    public String getName(int scene) {
        return names[scene];
    }

    /** First tick after the whole timeline. */
    public int getEndTick() {
        return endTick;
    }

    /** true when the tick is at or past the end (a negative tick is not finished, it is before the start). */
    public boolean isFinished(int tick) {
        return tick >= endTick;
    }

    /** Index of the scene that runs at the tick, or -1 before the start and from the end on. */
    public int sceneAt(int tick) {
        if (tick < 0 || tick >= endTick) {
            return -1;
        }
        int i = count - 1;
        while (i > 0 && starts[i] > tick) {
            i--;
        }
        return i;
    }

    /** Ticks since the start of the scene that runs at the tick, or -1 if no scene runs. */
    public int getSceneTick(int tick) {
        int s = sceneAt(tick);
        return s < 0 ? -1 : tick - starts[s];
    }
}
