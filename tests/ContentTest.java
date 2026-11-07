// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;

/** Headless tests for the model format, resource manager and level loader. */
public class ContentTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) < eps; }

    /** Assets backed by a folder plus in-memory overrides. */
    static final class FileAssets implements Assets {
        final File dir;
        final HashMap<String, byte[]> memory = new HashMap<String, byte[]>();
        int reads;
        FileAssets(String dir) { this.dir = new File(dir); }
        public byte[] readBytes(String path) {
            reads++;
            if (memory.containsKey(path)) return memory.get(path);
            File f = new File(dir, path);
            if (!f.exists()) return null;
            try {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toByteArray();
            } catch (java.io.IOException e) { return null; }
        }
    }

    /** Renderer that tracks which GPU resources are alive. */
    static final class TrackRenderer implements Renderer {
        int nextMesh, nextTex;
        final HashSet<Integer> liveMeshes = new HashSet<Integer>();
        final HashSet<Integer> liveTextures = new HashSet<Integer>();
        final HashSet<String> missingTextures = new HashSet<String>();
        final HashMap<Integer, int[]> meshTex = new HashMap<Integer, int[]>();
        int texLoads, meshCreates;
        public String getBackendName() { return "test"; }
        public int getWidth() { return 800; }
        public int getHeight() { return 480; }
        public int getDrawCalls() { return 0; }
        public int getTriangles() { return 0; }
        public void setFog(boolean enabled, float start, float end, float r, float g, float b) { }
        public void drawSky(int mesh) { }
        public void drawBillboards(int texture, float[] data, int count, boolean additive) { }
        public int createMesh(MeshData d) { meshCreates++; int id = nextMesh++; liveMeshes.add(id); return id; }
        public int loadTexture(String p, boolean s) { return loadTexture(p, s, false); }
        public int loadTexture(String p, boolean s, boolean r) {
            if (missingTextures.contains(p)) return -1;
            texLoads++; int id = nextTex++; liveTextures.add(id); return id;
        }
        public void deleteTexture(int t) { liveTextures.remove(t); }
        public void deleteMesh(int m) { liveMeshes.remove(m); meshTex.remove(m); }
        public void setMeshTextures(int m, int d, int l) { meshTex.put(m, new int[] {d, l}); }
        public boolean supportsLightmaps() { return true; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return 64; }
        public int getTextureHeight(int t) { return 64; }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean e) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float a, float b, float c, float d, float e, float f) { }
        public void clear(float r, float g, float b) { }
        public void setCamera(Mat4 p, Mat4 v) { }
        public void drawMesh(int m, Mat4 model) { }
        public void drawQuads(int t, float[] v, int n) { }
    }

    static ModelData sampleModel() {
        float[] pos = {0, 0, 0, 1, 0, 0, 0, 3, 4, 1, 1, 1};
        float[] nor = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] col = {1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0.5f, 1, 1, 1, 1};
        float[] uv0 = {0, 0, 1, 0, 0, 1, 1, 1};
        float[] uv1 = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};
        short[] idx = {0, 1, 2, 2, 1, 3};
        return new ModelData(new MeshData(pos, nor, col, uv0, uv1, idx), "wall.png", "lm.png");
    }

    static void expectBad(byte[] data, String name) {
        boolean ok = false;
        try { ModelFormat.parse(data); }
        catch (IllegalArgumentException e) { ok = true; }
        catch (RuntimeException e) { System.out.println("   unexpected " + e); }
        check(ok, name);
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "demos/demo-v0.5/assets";

        // ---------------- model format round trip
        ModelData sm = sampleModel();
        byte[] bytes = ModelFormat.write(sm);
        ModelData back = ModelFormat.parse(bytes);
        boolean same = back.mesh.getVertexCount() == 4 && back.mesh.getIndexCount() == 6
                && "wall.png".equals(back.diffuseTexture) && "lm.png".equals(back.lightmapTexture)
                && back.mesh.normals != null && back.mesh.uv0 != null && back.mesh.uv1 != null;
        for (int i = 0; same && i < 12; i++) same = back.mesh.positions[i] == sm.mesh.positions[i] && back.mesh.normals[i] == sm.mesh.normals[i];
        for (int i = 0; same && i < 16; i++) same = near(back.mesh.colors[i], sm.mesh.colors[i], 1f / 255f);
        for (int i = 0; same && i < 8; i++) same = back.mesh.uv0[i] == sm.mesh.uv0[i] && back.mesh.uv1[i] == sm.mesh.uv1[i];
        for (int i = 0; same && i < 6; i++) same = back.mesh.indices[i] == sm.mesh.indices[i];
        check(same, "model: write/parse round trip keeps every array and both texture names");
        check(near(back.boundRadius, 5f, 1e-5f), "model: bounding radius = farthest vertex from the origin (" + back.boundRadius + ")");
        ModelData plain = ModelFormat.parse(ModelFormat.write(new ModelData(
                new MeshData(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[12], new short[] {0, 1, 2}), null, null)));
        check(plain.diffuseTexture == null && plain.lightmapTexture == null && plain.mesh.normals == null
                && plain.mesh.uv0 == null && plain.mesh.uv1 == null, "model: optional parts are absent when not stored (empty names = null)");

        // ---------------- malformed files must be rejected with IllegalArgumentException
        boolean prefixesOk = true;
        for (int len = 0; len < bytes.length; len++) {
            byte[] cut = new byte[len];
            System.arraycopy(bytes, 0, cut, 0, len);
            try { ModelFormat.parse(cut); prefixesOk = false; }
            catch (IllegalArgumentException e) { /* expected */ }
            catch (RuntimeException e) { prefixesOk = false; System.out.println("   len " + len + ": " + e); }
        }
        check(prefixesOk, "model: every truncated prefix (" + bytes.length + " lengths) is rejected cleanly");
        byte[] bad = bytes.clone(); bad[0] = 'X';
        expectBad(bad, "model: bad magic is rejected");
        bad = bytes.clone(); bad[4] = 0; bad[5] = 0; bad[6] = 0; bad[7] = 0;
        expectBad(bad, "model: zero vertices is rejected");
        bad = bytes.clone(); bad[8] = 7;
        expectBad(bad, "model: index count not divisible by 3 is rejected");
        bad = bytes.clone(); bad[bad.length - 2] = 99;
        expectBad(bad, "model: index pointing outside the vertices is rejected");
        bad = bytes.clone(); bad[19] = 0x7f;
        expectBad(bad, "model: absurd string length is rejected");
        expectBad(null, "model: null data is rejected");

        // ---------------- files produced by the Python tools
        FileAssets assets = new FileAssets(dir);
        ModelData statue = ModelFormat.parse(assets.readBytes("statue.mdl"));
        check(statue.mesh.getVertexCount() == 143 && statue.mesh.getIndexCount() == 240 * 3 && statue.mesh.normals != null
                && statue.mesh.uv0 != null && statue.diffuseTexture == null && statue.lightmapTexture == null,
                "tools: statue.mdl (from OBJ) parses: 143 vertices, 240 triangles, normals, uv, no texture");
        check(near(statue.mesh.colors[0], 0.8f, 0.01f) && near(statue.mesh.colors[1], 0.55f, 0.01f) && near(statue.mesh.colors[2], 0.3f, 0.01f),
                "tools: OBJ material color Kd became the vertex color");
        ModelData gem = ModelFormat.parse(assets.readBytes("gem.mdl"));
        check(gem.mesh.getVertexCount() == 24 && gem.mesh.getIndexCount() == 24 && gem.mesh.normals != null && gem.mesh.uv0 == null,
                "tools: gem.mdl (from glTF) parses: 24 vertices, 8 triangles, no uv");
        boolean gemColors = false;
        for (int i = 4; i < 96; i += 4) if (Math.abs(gem.mesh.colors[i] - gem.mesh.colors[0]) > 0.05f) gemColors = true;
        check(gemColors, "tools: glTF COLOR_0 vertex colors were read");
        ModelData level = ModelFormat.parse(assets.readBytes("level.mdl"));
        check(level.mesh.getVertexCount() == 508 && level.mesh.getIndexCount() == 127 * 6
                && "stone.png".equals(level.diffuseTexture) && "lightmap.png".equals(level.lightmapTexture)
                && level.mesh.uv0 != null && level.mesh.uv1 != null && level.mesh.normals != null,
                "tools: level.mdl has both UV sets and both texture names (127 faces)");
        boolean uvRange = true;
        for (int i = 0; i < level.mesh.uv1.length; i++) if (level.mesh.uv1[i] < 0f || level.mesh.uv1[i] > 1f) uvRange = false;
        check(uvRange, "tools: lightmap UVs are inside 0..1");
        boolean winding = true;
        for (int mi = 0; mi < 3; mi++) {
            ModelData md = mi == 0 ? level : (mi == 1 ? statue : gem);
            float[] p = md.mesh.positions, n = md.mesh.normals;
            short[] ix = md.mesh.indices;
            for (int i = 0; i < ix.length; i += 3) {
                int a = ix[i], b = ix[i + 1], c = ix[i + 2];
                float ex = p[b*3]-p[a*3], ey = p[b*3+1]-p[a*3+1], ez = p[b*3+2]-p[a*3+2];
                float fx = p[c*3]-p[a*3], fy = p[c*3+1]-p[a*3+1], fz = p[c*3+2]-p[a*3+2];
                float gx = ey*fz-ez*fy, gy = ez*fx-ex*fz, gz = ex*fy-ey*fx;
                float gl = (float) Math.sqrt(gx*gx + gy*gy + gz*gz);
                if (gl < 1e-9f) continue; // degenerate (statue apex)
                float d = (gx*n[a*3] + gy*n[a*3+1] + gz*n[a*3+2]) / gl;
                if (d < 0.2f) winding = false;
            }
        }
        check(winding, "tools: all triangles of level, statue and gem are counter-clockwise (face normal agrees with vertex normal)");

        // ---------------- ResourceManager
        TrackRenderer tr = new TrackRenderer();
        ResourceManager res = new ResourceManager(assets, tr, 8, 8);
        int t1 = res.getTexture("stone.png", true, true);
        int t2 = res.getTexture("stone.png", true, true);
        int t3 = res.getTexture("stone.png", false, true);
        check(t1 >= 0 && t1 == t2 && t3 != t1 && tr.texLoads == 2 && res.getTextureCount() == 2, "resources: same texture + same flags is cached, different flags is a separate texture");
        res.releaseTexture(t1);
        check(tr.liveTextures.contains(t1) && res.getTextureCount() == 2, "resources: texture stays alive while referenced");
        res.releaseTexture(t1);
        check(!tr.liveTextures.contains(t1) && tr.liveTextures.contains(t3) && res.getTextureCount() == 1, "resources: texture is deleted when the last reference is released");
        res.releaseTexture(t3);
        check(tr.liveTextures.isEmpty() && res.getTextureCount() == 0 && res.getTotalRefs() == 0, "resources: table is empty again");

        ResourceManager.Model m1 = res.getModel("level.mdl");
        ResourceManager.Model m2 = res.getModel("level.mdl");
        check(m1 != null && m1 == m2 && tr.meshCreates == 1 && m1.hasLightmap() && res.getModelCount() == 1, "resources: model is loaded once and shared");
        check(tr.meshTex.get(m1.mesh)[0] == m1.diffuseTexture && tr.meshTex.get(m1.mesh)[1] == m1.lightmapTexture
                && m1.diffuseTexture >= 0 && m1.lightmapTexture >= 0 && near(m1.boundRadius, level.boundRadius, 1e-4f),
                "resources: textures are bound to the mesh, bounding radius comes from the file");
        int readsBefore = assets.reads;
        res.getModel("level.mdl");
        check(assets.reads == readsBefore, "resources: a cached model does not read the file again");
        res.releaseModel(m1); res.releaseModel(m1);
        check(tr.liveMeshes.size() == 1 && tr.liveTextures.size() == 2, "resources: model survives while references remain");
        res.releaseModel(m1);
        check(tr.liveMeshes.isEmpty() && tr.liveTextures.isEmpty() && res.getModelCount() == 0 && res.getTotalRefs() == 0,
                "resources: releasing the last reference frees the mesh and both textures");

        check(res.getModel("nothing.mdl") == null && res.getLastError().contains("nothing.mdl"), "resources: missing file -> null with an error message");
        assets.memory.put("broken.mdl", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24});
        check(res.getModel("broken.mdl") == null && res.getLastError().contains("broken.mdl"), "resources: corrupt file -> null with an error message");
        tr.missingTextures.add("lightmap.png");
        check(res.getModel("level.mdl") == null && tr.liveTextures.isEmpty() && tr.liveMeshes.isEmpty() && res.getTextureCount() == 0,
                "resources: a missing texture fails the model and leaks nothing");
        tr.missingTextures.clear();
        ResourceManager tiny = new ResourceManager(assets, tr, 1, 1);
        ResourceManager.Model tm = tiny.getModel("statue.mdl");
        check(tm != null && tiny.getModel("gem.mdl") == null && tiny.getLastError().contains("full"), "resources: model table full -> null");

        // ---------------- LevelLoader with the generated level
        TrackRenderer lr = new TrackRenderer();
        ResourceManager lres = new ResourceManager(assets, lr, 16, 16);
        Scene scene = new Scene(512);
        PhysicsWorld world = new PhysicsWorld(256, -21, 0, -21, 21, 12, 21, 3f);
        int freeNodes = scene.getFreeNodes(), freeBodies = world.getFreeBodies();
        Level lv = LevelLoader.load(assets.readBytes("level.lvl"), lres, scene, world);
        check(lv.getObjectCount() == 17 && lv.spawnCount == 4 && lv.getBodyCount() == 25, "level: 17 objects, 4 spawns, 25 physics boxes (" + lv.getObjectCount() + ", " + lv.spawnCount + ", " + lv.getBodyCount() + ")");
        check(lres.getModelCount() == 3 && lres.getTotalRefs() == 17 + lres.getTextureCount() * 0 + 2 + 0, "level: 3 distinct models shared by 17 objects (models=" + lres.getModelCount() + ", refs=" + lres.getTotalRefs() + ")");
        check(near(lv.camRadius, 42f, 1e-4f) && near(lv.camHeight, 34f, 1e-4f) && near(scene.ambientR, 0.36f, 1e-4f) && scene.lightDy < 0f && near(scene.lightR, 0.85f, 1e-4f),
                "level: camera, ambient and light lines were applied to the scene");
        scene.update();
        Node n0 = lv.getNode(0);
        check("level.mdl".equals(lv.getModel(0).name) && n0.mesh == lv.getModel(0).mesh && near(n0.boundRadius, level.boundRadius, 1e-4f) && n0.worldVisible, "level: first object is the level geometry");
        int gems = 0;
        for (int i = 0; i < lv.getObjectCount(); i++) if ("gem.mdl".equals(lv.getModel(i).name)) { gems++; check(near(lv.getNode(i).sx, 0.45f, 1e-4f), "level: gem scale parsed"); break; }
        check(gems == 1, "level: gem objects found by model name");
        lv.unload(scene, world, lres);
        check(scene.getFreeNodes() == freeNodes && world.getFreeBodies() == freeBodies && lres.getModelCount() == 0 && lres.getTotalRefs() == 0
                && lr.liveMeshes.isEmpty() && lr.liveTextures.isEmpty(), "level: unload gives back every node, body, mesh and texture");

        // reload: load the new copy first, then unload the old one (resources stay cached)
        Level a = LevelLoader.load(assets.readBytes("level.lvl"), lres, scene, world);
        int loadsAfterFirst = lr.texLoads, meshesAfterFirst = lr.meshCreates;
        Level b = LevelLoader.load(assets.readBytes("level.lvl"), lres, scene, world);
        check(lr.texLoads == loadsAfterFirst && lr.meshCreates == meshesAfterFirst && lres.getModelCount() == 3 && lres.getTotalRefs() > 17,
                "level: loading it again reuses the cached models and textures (no new GPU resources)");
        a.unload(scene, world, lres);
        check(lr.liveMeshes.size() == 3 && lres.getModelCount() == 3, "level: unloading the old copy keeps what the new copy uses");
        b.unload(scene, world, lres);

        // error handling: nothing may leak
        String[] badLevels = {
            "object level.mdl 0 0 0\nbogus 1 2 3\n",
            "object level.mdl 0 0 0\nobject missing.mdl\n",
            "object statue.mdl 1 2 x\n",
            "ambient 1 2\n",
            "object level.mdl 0 0 0\nspawn 1 2\n"};
        String[] want = {"line 2", "line 2", "line 1", "line 1", "line 2"};
        for (int i = 0; i < badLevels.length; i++) {
            String msg = "";
            try { LevelLoader.load(badLevels[i].getBytes(), lres, scene, world); }
            catch (IllegalArgumentException e) { msg = e.getMessage(); }
            check(msg.startsWith(want[i]) && scene.getFreeNodes() == freeNodes && lres.getTotalRefs() == 0 && lr.liveMeshes.isEmpty()
                    && lr.liveTextures.isEmpty() && world.getFreeBodies() == freeBodies,
                    "level: error '" + msg + "' is reported with its line and cleans up");
        }
        Scene tinyScene = new Scene(2);
        boolean full = false;
        try { LevelLoader.load("object statue.mdl\nobject statue.mdl\nobject statue.mdl\n".getBytes(), lres, tinyScene, null); }
        catch (IllegalStateException e) { full = true; }
        check(full && tinyScene.getFreeNodes() == 2 && lres.getTotalRefs() == 0 && lr.liveMeshes.isEmpty(), "level: full scene -> IllegalStateException and clean state");
        Level noWorld = LevelLoader.load("# only a comment\n\n box 0 0 0 1 1 1   # ignored without a world\nspawn 1 2 3\n".getBytes(), lres, scene, null);
        check(noWorld.getBodyCount() == 0 && noWorld.spawnCount == 1 && near(noWorld.spawns[2], 3f, 1e-6f), "level: comments, blank lines, 'box' ignored without a PhysicsWorld");

        // ---------------- engine asset access
        Engine e = new Engine();
        e.setAssets(assets);
        check(e.getAssets() == assets, "engine: assets accessor");

        System.out.println(failed == 0 ? "ALL ASSET TESTS PASSED" : (failed + " ASSET TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
