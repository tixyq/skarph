// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A level loaded from a level file by LevelLoader: remembers what it created
 * so that unload() can remove it again.
 */
public final class Level {
    /** Default capacities of new Level() (use the three-argument constructor for bigger or smaller levels). */
    public static final int MAX_OBJECTS = 256;
    public static final int MAX_SPAWNS = 64;
    public static final int MAX_BODIES = 128;

    /** Capacity of this level: objects (nodes with models), spawn points, physics bodies. */
    public final int maxObjects;
    public final int maxSpawns;
    public final int maxBodies;

    /** Camera hints from the file: orbit target, distance and height. */
    public float camTargetX;
    public float camTargetY;
    public float camTargetZ;
    public float camRadius = 20f;
    public float camHeight = 10f;

    /** Spawn points, 3 floats each. */
    public final float[] spawns;
    public int spawnCount;

    final Node[] nodes;
    final ResourceManager.Model[] models;
    int objectCount;
    final Body[] bodies;
    int bodyCount;

    /** A level with the default capacities. */
    public Level() {
        this(MAX_OBJECTS, MAX_SPAWNS, MAX_BODIES);
    }

    /** A level with its own capacities (allocates the tables once; call it in init()). */
    public Level(int maxObjects, int maxSpawns, int maxBodies) {
        this.maxObjects = maxObjects;
        this.maxSpawns = maxSpawns;
        this.maxBodies = maxBodies;
        spawns = new float[maxSpawns * 3];
        nodes = new Node[maxObjects];
        models = new ResourceManager.Model[maxObjects];
        bodies = new Body[maxBodies];
    }

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
