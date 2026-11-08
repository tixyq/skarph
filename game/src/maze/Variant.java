package maze;

import java.util.Random;

/**
 * One look of a maze: time of day x wall type x season. A variant is a number 0..15:
 * bit 3 = night, bit 2 = leaves instead of brick, bits 0..1 = season (spring, summer, autumn, winter).
 * Season sets the brick color, the leaf color and the grass color together.
 */
public final class Variant {
    public static final int COUNT = 16;

    private static final float[][] BRICK = {
        {0.85f, 0.42f, 0.33f}, {0.88f, 0.74f, 0.48f}, {0.62f, 0.36f, 0.30f}, {0.62f, 0.68f, 0.80f}};
    private static final float[][] LEAF = {
        {0.62f, 0.92f, 0.38f}, {0.22f, 0.66f, 0.22f}, {0.95f, 0.48f, 0.12f}, {0.90f, 0.94f, 1.00f}};
    private static final float[][] GRASS = {
        {0.48f, 0.86f, 0.32f}, {0.28f, 0.66f, 0.22f}, {0.70f, 0.58f, 0.28f}, {0.94f, 0.96f, 1.00f}};
    private static final float[][] DAY_SKY = {
        {0.60f, 0.80f, 0.96f}, {0.50f, 0.74f, 0.98f}, {0.72f, 0.78f, 0.84f}, {0.82f, 0.87f, 0.93f}};

    private Variant() {
    }

    public static boolean isNight(int v) { return (v & 8) != 0; }
    public static boolean isLeaves(int v) { return (v & 4) != 0; }
    public static int season(int v) { return v & 3; }

    public static float[] wallColor(int v) { return isLeaves(v) ? LEAF[v & 3] : BRICK[v & 3]; }
    public static float[] floorColor(int v) { return GRASS[v & 3]; }
    /** Sky and fog color: the clear color. */
    public static float[] clearColor(int v) { return isNight(v) ? NIGHT_SKY : DAY_SKY[v & 3]; }
    private static final float[] NIGHT_SKY = {0.06f, 0.09f, 0.19f};

    public static float wallHeight(int v) { return isLeaves(v) ? 1.7f : 2.6f; }

    public static float fogStart(int v) { return isNight(v) ? 5f : 30f; }
    public static float fogEnd(int v) { return isNight(v) ? 24f : 85f; }

    /** Directional light: direction the light travels (x, y, z) and color (r, g, b). */
    public static float[] light(int v) { return isNight(v) ? MOON : SUN; }
    public static float[] ambient(int v) { return isNight(v) ? NIGHT_AMBIENT : DAY_AMBIENT; }
    private static final float[] SUN = {-0.45f, -1f, -0.30f, 1.00f, 0.96f, 0.86f};
    private static final float[] MOON = {0.40f, -1f, 0.35f, 0.42f, 0.46f, 0.66f};
    private static final float[] DAY_AMBIENT = {0.52f, 0.54f, 0.58f};
    private static final float[] NIGHT_AMBIENT = {0.30f, 0.33f, 0.46f};

    /** A random variant that differs from both given ones (pass -1 for none). */
    public static int pick(Random rnd, int not1, int not2) {
        int v;
        do {
            v = rnd.nextInt(COUNT);
        } while (v == not1 || v == not2);
        return v;
    }
}
