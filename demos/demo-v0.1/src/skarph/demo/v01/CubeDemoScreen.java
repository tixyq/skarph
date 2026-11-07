// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v01;

import skarph.core.Engine;
import skarph.core.InputEvent;
import skarph.core.Mat4;
import skarph.core.Renderer;
import skarph.core.ScreenAdapter;
import skarph.core.Shapes;

/**
 * Tech demo v0.1: one big cube and 8 small orbiting cubes.
 * Everything is driven by the tick counter (60 ticks/s), not by time.
 * Touch and drag rotates the big cube.
 */
public final class CubeDemoScreen extends ScreenAdapter {
    private static final int SMALL_CUBES = 8;
    private static final int ORBIT_PERIOD_TICKS = 720; // 0.5 degrees per tick

    private Engine engine;
    private int cubeMesh;

    private final Mat4 projection = new Mat4();
    private final Mat4 view = new Mat4();
    private final Mat4 model = new Mat4();
    private final InputEvent event = new InputEvent();

    private int lastWidth;
    private int lastHeight;
    private float angleX;
    private float angleY;
    private int orbitTick;
    private boolean dragging;
    private float lastX;
    private float lastY;

    public void init(Engine engine, Renderer renderer) {
        this.engine = engine;
        cubeMesh = renderer.createMesh(Shapes.cube(1f));
        view.setLookAt(0f, 3f, 9f, 0f, 0f, 0f, 0f, 1f, 0f);
    }

    public void update() {
        while (engine.input.poll(event)) {
            switch (event.type) {
                case InputEvent.DOWN:
                    dragging = true;
                    lastX = event.x;
                    lastY = event.y;
                    break;
                case InputEvent.MOVE:
                    if (dragging) {
                        angleY += (event.x - lastX) * 0.5f;
                        angleX += (event.y - lastY) * 0.5f;
                        lastX = event.x;
                        lastY = event.y;
                    }
                    break;
                case InputEvent.UP:
                    dragging = false;
                    break;
                default:
                    break;
            }
        }
        if (!dragging) {
            angleY += 1f; // exactly 60 degrees per second
        }
        if (angleY >= 360f) {
            angleY -= 360f;
        }
        if (++orbitTick >= ORBIT_PERIOD_TICKS) {
            orbitTick = 0;
        }
    }

    public void render(Renderer r) {
        int w = r.getWidth();
        int h = r.getHeight();
        if (h > 0 && (w != lastWidth || h != lastHeight)) {
            lastWidth = w;
            lastHeight = h;
            projection.setPerspective(45f, (float) w / (float) h, 0.5f, 50f);
        }

        r.clear(0.08f, 0.09f, 0.12f);
        r.setCamera(projection, view);

        model.setIdentity();
        model.rotate(angleX, 1f, 0f, 0f);
        model.rotate(angleY, 0f, 1f, 0f);
        model.scale(2f, 2f, 2f);
        r.drawMesh(cubeMesh, model);

        for (int i = 0; i < SMALL_CUBES; i++) {
            float a = orbitTick * 0.5f + i * (360f / SMALL_CUBES);
            model.setIdentity();
            model.rotate(a, 0f, 1f, 0f);
            model.translate(4.5f, 0f, 0f);
            model.rotate(orbitTick * 2f, 1f, 1f, 0f);
            model.scale(0.6f, 0.6f, 0.6f);
            r.drawMesh(cubeMesh, model);
        }
    }
}
