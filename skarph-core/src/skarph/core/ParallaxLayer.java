// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A background picture that scrolls slower than the world and repeats sideways (sky, hills, clouds). Draw it before the
 * tile map, in the same SpriteBatch pass. The region should be at least as wide as it needs to tile seamlessly.
 */
public final class ParallaxLayer {
    private final TextureRegion region;
    private final float factorX;
    private final float factorY;
    private final float offsetY;
    private final boolean repeatX;
    private final float width;
    private final float height;

    /**
     * @param factorX,factorY 0 = stuck to the screen, 1 = moves with the world
     * @param offsetY where the picture's top is when the camera is at y = 0 (world units)
     * @param repeatX tile horizontally
     */
    public ParallaxLayer(TextureRegion region, float width, float height, float factorX, float factorY, float offsetY, boolean repeatX) {
        this.region = region;
        this.width = width;
        this.height = height;
        this.factorX = factorX;
        this.factorY = factorY;
        this.offsetY = offsetY;
        this.repeatX = repeatX;
    }

    public void draw(SpriteBatch batch, Camera2D cam) {
        float left = cam.getLeft();
        float y = offsetY + cam.getTop() * (1f - factorY);
        float shift = left * (1f - factorX);          // the layer is drawn at world x = left - left*factor + k*width
        if (!repeatX) {
            batch.draw(region, shift, y, width, height);
            return;
        }
        float visibleLeft = left - shift;             // position inside the picture strip
        float start = left - (visibleLeft - (float) FastMath.floor(visibleLeft / width) * width);
        for (float x = start; x < cam.getRight(); x += width) {
            batch.draw(region, x, y, width, height);
        }
    }
}
