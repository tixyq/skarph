// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Loads a level from a text file. One command per line, '#' starts a comment:
 * <pre>
 *   ambient r g b                       scene ambient light
 *   light dx dy dz r g b                scene directional light (direction the light travels)
 *   camera tx ty tz radius height       orbit camera hints
 *   object file.mdl [x y z [rx ry rz [sx sy sz]]]   place a model (missing values: 0, 0, 0, 0,0,0, 1,1,1)
 *   box cx cy cz hx hy hz [mass]        physics box (mass 0 or missing = static); needs a PhysicsWorld
 *   spawn x y z                         a spawn point
 * </pre>
 * Load-time code: allocates, never call it from update()/render().
 */
public final class LevelLoader {
    private LevelLoader() {
    }

    /**
     * @param world may be null: "box" lines are then ignored
     * @return the level; on any error everything created so far is removed again
     * @throws IllegalArgumentException with the line number for syntax errors or missing files
     * @throws IllegalStateException if the scene or the world is full
     */
    public static Level load(byte[] data, ResourceManager res, Scene scene, PhysicsWorld world) {
        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported");
        }
        Level level = new Level();
        try {
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
                parseLine(line.split("\\s+"), ln + 1, level, res, scene, world);
            }
        } catch (RuntimeException e) {
            level.unload(scene, world, res);
            throw e;
        }
        return level;
    }

    private static float num(String[] t, int i, float def, int line) {
        if (i >= t.length) {
            return def;
        }
        try {
            return Float.parseFloat(t[i]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + line + ": bad number '" + t[i] + "'");
        }
    }

    private static void need(String[] t, int count, int line) {
        if (t.length < count) {
            throw new IllegalArgumentException("line " + line + ": '" + t[0] + "' needs "
                    + (count - 1) + " values");
        }
    }

    private static void parseLine(String[] t, int line, Level level, ResourceManager res,
                                  Scene scene, PhysicsWorld world) {
        String cmd = t[0];
        if (cmd.equals("ambient")) {
            need(t, 4, line);
            scene.ambientR = num(t, 1, 0f, line);
            scene.ambientG = num(t, 2, 0f, line);
            scene.ambientB = num(t, 3, 0f, line);
        } else if (cmd.equals("light")) {
            need(t, 7, line);
            scene.lightDx = num(t, 1, 0f, line);
            scene.lightDy = num(t, 2, 0f, line);
            scene.lightDz = num(t, 3, 0f, line);
            scene.lightR = num(t, 4, 1f, line);
            scene.lightG = num(t, 5, 1f, line);
            scene.lightB = num(t, 6, 1f, line);
        } else if (cmd.equals("camera")) {
            need(t, 6, line);
            level.camTargetX = num(t, 1, 0f, line);
            level.camTargetY = num(t, 2, 0f, line);
            level.camTargetZ = num(t, 3, 0f, line);
            level.camRadius = num(t, 4, 20f, line);
            level.camHeight = num(t, 5, 10f, line);
        } else if (cmd.equals("object")) {
            need(t, 2, line);
            if (level.objectCount >= Level.MAX_OBJECTS) {
                throw new IllegalStateException("level has too many objects");
            }
            ResourceManager.Model m = res.getModel(t[1]);
            if (m == null) {
                throw new IllegalArgumentException("line " + line + ": " + res.getLastError());
            }
            Node n = scene.createNode(null);
            if (n == null) {
                res.releaseModel(m);
                throw new IllegalStateException("scene is full");
            }
            // register first: if a number below is malformed, unload() cleans up node and model
            level.nodes[level.objectCount] = n;
            level.models[level.objectCount] = m;
            level.objectCount++;
            n.mesh = m.mesh;
            n.boundRadius = m.boundRadius;
            n.setPosition(num(t, 2, 0f, line), num(t, 3, 0f, line), num(t, 4, 0f, line));
            n.setRotation(num(t, 5, 0f, line), num(t, 6, 0f, line), num(t, 7, 0f, line));
            n.setScale(num(t, 8, 1f, line), num(t, 9, 1f, line), num(t, 10, 1f, line));
        } else if (cmd.equals("box")) {
            need(t, 7, line);
            if (world == null) {
                return;
            }
            if (level.bodyCount >= Level.MAX_BODIES) {
                throw new IllegalStateException("level has too many boxes");
            }
            Body b = world.createBox(num(t, 1, 0f, line), num(t, 2, 0f, line), num(t, 3, 0f, line),
                    num(t, 4, 0.5f, line), num(t, 5, 0.5f, line), num(t, 6, 0.5f, line),
                    num(t, 7, 0f, line));
            if (b == null) {
                throw new IllegalStateException("physics world is full");
            }
            level.bodies[level.bodyCount++] = b;
        } else if (cmd.equals("spawn")) {
            need(t, 4, line);
            if (level.spawnCount >= Level.MAX_SPAWNS) {
                throw new IllegalStateException("level has too many spawn points");
            }
            int i = level.spawnCount++ * 3;
            level.spawns[i] = num(t, 1, 0f, line);
            level.spawns[i + 1] = num(t, 2, 0f, line);
            level.spawns[i + 2] = num(t, 3, 0f, line);
        } else {
            throw new IllegalArgumentException("line " + line + ": unknown command '" + cmd + "'");
        }
    }
}
