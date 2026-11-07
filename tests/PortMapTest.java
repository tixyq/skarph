// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;

/** Loads the generated port demo map (night winter port; args[0] = assets folder) the way the demo will and checks its limits. */
public class PortMapTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean potTexture(String dir, String name) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(dir, name));
            return img != null && Integer.bitCount(img.getWidth()) == 1 && Integer.bitCount(img.getHeight()) == 1
                    && img.getWidth() <= 1024 && img.getHeight() <= 1024;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "demos/demo-v1.0/assets";
        ContentTest.FileAssets assets = new ContentTest.FileAssets(dir);

        String[] names = {"terrain.mdl", "buildings.mdl", "water.mdl", "pine.mdl", "spruce.mdl", "rock.mdl"};
        boolean parsed = true, hasNormals = true, uvOk = true, texOk = true;
        int lightmapped = 0;
        for (int i = 0; i < names.length; i++) {
            try {
                ModelData m = ModelFormat.parse(assets.readBytes(names[i]));
                hasNormals &= m.mesh.normals != null;
                if (m.diffuseTexture != null) texOk &= potTexture(dir, m.diffuseTexture);
                if (m.lightmapTexture != null) {
                    texOk &= potTexture(dir, m.lightmapTexture);
                    lightmapped++;
                    boolean has = m.mesh.uv1 != null && m.mesh.uv0 != null && m.diffuseTexture != null;
                    for (int k = 0; has && k < m.mesh.uv1.length; k++) has = m.mesh.uv1[k] >= 0f && m.mesh.uv1[k] <= 1f;
                    uvOk &= has;
                }
            } catch (RuntimeException e) {
                parsed = false;
                System.out.println("   " + names[i] + ": " + e);
            }
        }
        check(parsed, "port: all " + names.length + " models parse");
        check(hasNormals, "port: all models have normals");
        check(lightmapped == 2 && uvOk, "port: terrain and buildings are lightmapped (uv0 + uv1 inside 0..1, a diffuse texture)");
        check(texOk, "port: every texture exists, is a power of two and at most 1024 x 1024");

        // terrain: quay plateau flat at y = 0 (the physics floor) except the fjord; the fjord is below the water level
        MeshData t = ModelFormat.parse(assets.readBytes("terrain.mdl")).mesh;
        float maxY = 0f, minY = 0f, plateauErr = 0f;
        for (int i = 0; i < t.getVertexCount(); i++) {
            float x = t.positions[i * 3], y = t.positions[i * 3 + 1], z = t.positions[i * 3 + 2];
            maxY = Math.max(maxY, y);
            minY = Math.min(minY, y);
            if (Math.sqrt(x * x + z * z) < 31f && x > -23f) plateauErr = Math.max(plateauErr, Math.abs(y));
        }
        check(plateauErr < 1e-4f, "port: the quay (radius 31, x > -23) is flat at y = 0 (error " + plateauErr + ")");
        check(minY < -3f && minY > -6f, "port: the fjord reaches a seabed (" + minY + ")");
        check(maxY > 10f && maxY < 60f, "port: mountains reach a sensible height (" + maxY + ")");
        MeshData w = ModelFormat.parse(assets.readBytes("water.mdl")).mesh;
        boolean flat = true;
        for (int i = 0; i < w.getVertexCount(); i++) flat &= Math.abs(w.positions[i * 3 + 1] + 1f) < 1e-4f;
        check(flat, "port: the water is one flat sheet at y = -1");

        ContentTest.TrackRenderer tr = new ContentTest.TrackRenderer();
        ResourceManager res = new ResourceManager(assets, tr, 8, 16);
        Scene scene = new Scene(Level.MAX_OBJECTS + 8);
        PhysicsWorld world = new PhysicsWorld(Level.MAX_BODIES + 64, -26f, 0f, -26f, 26f, 40f, 26f, 3f);
        Level level = LevelLoader.load(assets.readBytes("map.lvl"), res, scene, world);
        check(level.getObjectCount() >= 150 && level.getObjectCount() <= Level.MAX_OBJECTS, "port: " + level.getObjectCount() + " objects (limit " + Level.MAX_OBJECTS + ")");
        check(level.getBodyCount() > 20 && level.getBodyCount() <= Level.MAX_BODIES, "port: " + level.getBodyCount() + " static boxes (limit " + Level.MAX_BODIES + ")");
        check(level.spawnCount == 4, "port: 4 spawn points");
        check(res.getModelCount() == names.length && tr.liveMeshes.size() == names.length, "port: every model is loaded once and shared (" + res.getModelCount() + " models, " + tr.liveMeshes.size() + " meshes)");

        boolean inArena = true;
        for (int i = 0; i < level.spawnCount; i++) {
            float x = level.spawns[i * 3], y = level.spawns[i * 3 + 1], z = level.spawns[i * 3 + 2];
            inArena &= Math.abs(x) < 25f && Math.abs(z) < 25f && y > 1f;
        }
        check(inArena, "port: spawn points are inside the barrier");

        Body ball = world.createSphere(4f, 10f, 4f, 0.5f, 1f);
        for (int i = 0; i < 600; i++) world.step();
        check(ball.y > 1.5f && ball.y < 2.0f && Math.abs(ball.vy) < 0.01f, "port: a ball dropped above the platform rests on its top (y " + ball.y + ")");

        // the barrier keeps things in: a ball shot at every side (also through the pier and road gaps) stays inside
        float[][] shots = {{-24f, 0.6f, 0f, -12f, 0f}, {24f, 0.6f, 0f, 12f, 0f}, {0f, 0.6f, 24f, 0f, 12f}, {0f, 0.6f, -24f, 0f, -12f}};
        boolean kept = true;
        for (int s = 0; s < shots.length; s++) {
            Body b = world.createSphere(shots[s][0], shots[s][1], shots[s][2], 0.5f, 1f);
            b.vx = shots[s][3] / 60f;
            b.vz = shots[s][4] / 60f;
            for (int i = 0; i < 300; i++) world.step();
            kept &= Math.abs(b.x) < 25.6f && Math.abs(b.z) < 25.6f;
        }
        check(kept, "port: the barrier (and the invisible gap boxes) keep balls inside the arena");

        level.unload(scene, world, res);
        check(res.getModelCount() == 0 && tr.liveMeshes.isEmpty(), "port: unload frees everything");
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
