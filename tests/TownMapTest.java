// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

/** Loads the generated town demo map (args[0] = assets folder) the way the demo will and checks its limits. */
public class TownMapTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "demos/demo-v0.8/assets";
        ContentTest.FileAssets assets = new ContentTest.FileAssets(dir);

        String[] names = {"terrain.mdl", "buildings.mdl", "pine.mdl", "oak.mdl", "rock.mdl", "lamp.mdl", "statue.mdl", "gem.mdl"};
        boolean parsed = true, hasNormals = true, uvOk = true, texOk = true;
        int lightmapped = 0;
        for (int i = 0; i < names.length; i++) {
            try {
                ModelData m = ModelFormat.parse(assets.readBytes(names[i]));
                hasNormals &= m.mesh.normals != null;
                String[] tex = {m.diffuseTexture, m.lightmapTexture};
                for (int k = 0; k < 2; k++) {
                    if (tex[k] == null) continue;
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(dir, tex[k]));
                        boolean pot = img != null && Integer.bitCount(img.getWidth()) == 1 && Integer.bitCount(img.getHeight()) == 1
                                && img.getWidth() <= 1024 && img.getHeight() <= 1024;
                        if (!pot) System.out.println("   " + tex[k] + " is missing, not a power of two or bigger than 1024");
                        texOk &= pot;
                    } catch (java.io.IOException e) { texOk = false; System.out.println("   " + tex[k] + ": " + e); }
                }
                if (m.lightmapTexture != null) {
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
        check(parsed, "map: all " + names.length + " models parse");
        check(hasNormals, "map: all models have normals (vertex lighting)");
        check(lightmapped == 2 && uvOk, "map: terrain and buildings are lightmapped (uv0 + uv1 inside 0..1, a diffuse texture)");
        check(texOk, "map: every texture exists, is a power of two and at most 1024 x 1024");

        // terrain: the plateau is flat at y = 0 (it is the physics floor), the mountains are high
        MeshData t = ModelFormat.parse(assets.readBytes("terrain.mdl")).mesh;
        float maxY = 0f, plateauErr = 0f;
        for (int i = 0; i < t.getVertexCount(); i++) {
            float x = t.positions[i * 3], y = t.positions[i * 3 + 1], z = t.positions[i * 3 + 2];
            maxY = Math.max(maxY, y);
            if (Math.sqrt(x * x + z * z) < 31f) plateauErr = Math.max(plateauErr, Math.abs(y));
        }
        check(plateauErr < 1e-4f, "map: the plateau (radius 31) is flat at y = 0 (error " + plateauErr + ")");
        check(maxY > 10f && maxY < 60f, "map: mountains reach a sensible height (" + maxY + ")");
        check(t.getIndexCount() / 3 > 4000, "map: terrain has " + t.getIndexCount() / 3 + " triangles");

        ContentTest.TrackRenderer tr = new ContentTest.TrackRenderer();
        ResourceManager res = new ResourceManager(assets, tr, 8, 16);
        Scene scene = new Scene(Level.MAX_OBJECTS + 8);
        PhysicsWorld world = new PhysicsWorld(Level.MAX_BODIES + 64, -26f, 0f, -26f, 26f, 40f, 26f, 3f);
        Level level = LevelLoader.load(assets.readBytes("map.lvl"), res, scene, world);
        check(level.getObjectCount() >= 150 && level.getObjectCount() <= Level.MAX_OBJECTS, "map: " + level.getObjectCount() + " objects (limit " + Level.MAX_OBJECTS + ")");
        check(level.getBodyCount() > 20 && level.getBodyCount() <= Level.MAX_BODIES, "map: " + level.getBodyCount() + " static boxes (limit " + Level.MAX_BODIES + ")");
        check(level.spawnCount == 4, "map: 4 spawn points");
        check(res.getModelCount() == 8 && tr.liveMeshes.size() == 8, "map: every model is loaded once and shared (" + res.getModelCount() + " models, " + tr.liveMeshes.size() + " meshes)");

        boolean inArena = true;
        for (int i = 0; i < level.spawnCount; i++) {
            float x = level.spawns[i * 3], y = level.spawns[i * 3 + 1], z = level.spawns[i * 3 + 2];
            inArena &= Math.abs(x) < 25f && Math.abs(z) < 25f && y > 1f;
        }
        check(inArena, "map: spawn points are inside the wall");

        // the physics floor must lie under the town: drop a sphere on the platform and let it rest
        Body ball = world.createSphere(4f, 10f, 4f, 0.5f, 1f);
        for (int i = 0; i < 600; i++) world.step();
        check(ball.y > 1.5f && ball.y < 2.0f && Math.abs(ball.vy) < 0.01f, "map: a ball dropped above the platform rests on its top (y " + ball.y + ")");

        level.unload(scene, world, res);
        check(res.getModelCount() == 0 && tr.liveMeshes.isEmpty(), "map: unload frees everything");
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
