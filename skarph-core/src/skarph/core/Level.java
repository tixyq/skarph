// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A level loaded from a level file by LevelLoader: remembers what it created
 * so that unload() can remove it again.
 */
public final class Level {
    public static final int MAX_OBJECTS = 256;
    public static final int MAX_SPAWNS = 64;
    public static final int MAX_BODIES = 128;

    /** Camera hints from the file: orbit target, distance and height. */
    public float camTargetX;
    public float camTargetY;
    public float camTargetZ;
    public float camRadius = 20f;
    public float camHeight = 10f;

    /** Spawn points, 3 floats each. */
    public final float[] spawns = new float[MAX_SPAWNS * 3];
    public int spawnCount;

    final Node[] nodes = new Node[MAX_OBJECTS];
    final ResourceManager.Model[] models = new ResourceManager.Model[MAX_OBJECTS];
    int objectCount;
    final Body[] bodies = new Body[MAX_BODIES];
    int bodyCount;

    public int getObjectCount() {
        return objectCount;
    }

    public Node getNode(int i) {
        return nodes[i];
    }

    public ResourceManager.Model getModel(int i) {
        return models[i];
    }

    public int getBodyCount() {
        return bodyCount;
    }

    /** Removes the level's nodes and bodies and releases its models. */
    public void unload(Scene scene, PhysicsWorld world, ResourceManager res) {
        for (int i = 0; i < objectCount; i++) {
            scene.destroyNode(nodes[i]);
            res.releaseModel(models[i]);
            nodes[i] = null;
            models[i] = null;
        }
        objectCount = 0;
        for (int i = 0; i < bodyCount; i++) {
            if (world != null) {
                world.destroyBody(bodies[i]);
            }
            bodies[i] = null;
        }
        bodyCount = 0;
        spawnCount = 0;
    }
}
