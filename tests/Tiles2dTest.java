// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import java.nio.file.*;
import skarph.core.*;

/** TileMap, TileSet, Camera2D, Body2D, Collision2D, ParallaxLayer and the SMP1 loader. */
public class Tiles2dTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public static void main(String[] args) throws Exception {
        TextUiTest.Rec rec = new TextUiTest.Rec();
        SpriteBatch batch = new SpriteBatch(rec, 1024);
        TileSet set = new TileSet(1, 64, 64, 16, 16);
        check(set.getTileCount() == 16 && set.get(1) != null && set.get(0) == null && set.get(17) == null, "tileset: ids are 1-based, 0 is empty");
        set.setAnimation(5, new int[] {5, 6, 7}, 4);
        TextureRegion r0 = set.get(5);
        for (int i = 0; i < 4; i++) set.update();
        check(set.get(5) != r0 && set.get(5) == set.get(6), "tileset: animated tile advances");

        // ---- loader
        TileMap sm = TileMap.load(Files.readAllBytes(Paths.get("tests/data/sample.map")), set);
        check(sm != null && sm.getWidth() == 8 && sm.getHeight() == 6 && sm.getLayerCount() == 2, "map: SMP1 loads");
        byte[] huge = new byte[40];
        huge[0] = 'S'; huge[1] = 'M'; huge[2] = 'P'; huge[3] = '1'; huge[4] = (byte) 255; huge[5] = (byte) 255; huge[6] = (byte) 255; huge[7] = (byte) 255; huge[12] = 3;
        check(TileMap.load(huge, set) == null, "map: a header that promises 65535 x 65535 tiles is refused without allocating");
        check(TileMap.load(new byte[] {1, 2, 3}, set) == null && TileMap.load(null, set) == null, "map: garbage gives null");
        check(sm.getCollisionLayer() == 1 && sm.getTile(1, 0, 0) == 1 && sm.getTile(1, 1, 0) == 0 && sm.getTile(0, 3, 3) == 4, "map: layers and the collision layer");
        check(sm.getFlags(1) == TileMap.SOLID && sm.getFlags(2) == TileMap.ONEWAY && sm.getFlags(3) == (TileMap.LADDER | TileMap.USER1), "map: tile flags from properties");
        check(sm.getObjectCount() == 2 && sm.getObjectName(0).equals("hero") && sm.getObjectType(1).equals("enemy")
                && near(sm.getObjectX(0), 24) && near(sm.getObjectWidth(1), 16), "map: objects");
        check(sm.flagsAt(0, 0) == TileMap.SOLID && sm.flagsAt(-1, 0) == 0, "map: flagsAt, outside is empty");
        sm.outsideSolid = true;
        check(sm.flagsAt(-1, 0) == TileMap.SOLID && sm.flagsAt(100, 100) == TileMap.SOLID, "map: outsideSolid");
        sm.outsideSolid = false;

        // ---- a map built in code: 30 x 20 tiles of 16 px, floor on row 15, a wall, a one-way platform
        TileMap map = new TileMap(30, 20, 3, set);
        map.addLayer(); map.addLayer();
        map.setFlags(1, TileMap.SOLID);
        map.setFlags(2, TileMap.ONEWAY);
        map.setFlags(3, TileMap.WATER);
        map.setCollisionLayer(0);
        for (int x = 0; x < 30; x++) map.setTile(0, x, 15, 1);
        for (int y = 0; y < 15; y++) map.setTile(0, 20, y, 1);
        for (int x = 5; x < 9; x++) map.setTile(0, x, 11, 2);
        map.setTile(0, 12, 14, 3);
        for (int x = 0; x < 30; x++) for (int y = 0; y < 20; y++) map.setTile(1, x, y, 5);

        // ---- bodies
        Body2D b = new Body2D(40, 100, 6, 8);
        b.gravity = 0.3f;
        for (int i = 0; i < 100; i++) b.update(map);
        check(b.onGround && near(b.y, 15 * 16 - 8) && b.vy == 0f, "body: falls and lands on the floor (y = " + b.y + ")");
        b.vx = 2f;
        for (int i = 0; i < 200; i++) { b.vx = 2f; b.update(map); }
        check(b.hitRight && near(b.x, 20 * 16 - 6) && b.onGround, "body: walks into a wall and stops (x = " + b.x + ")");
        b.vx = -2f;
        b.update(map);
        check(!b.hitRight && b.x < 20 * 16 - 6, "body: walks away from the wall");
        // tunnelling: a very fast fall
        Body2D fast = new Body2D(40, 20, 6, 8);
        fast.vy = 300f;
        fast.maxFall = 500f;
        fast.update(map);
        check(fast.onGround && near(fast.y, 15 * 16 - 8), "body: a 300 px/tick fall does not tunnel through the floor");
        Body2D shot = new Body2D(10, 100, 3, 3);
        shot.vx = 500f;
        shot.update(map);
        check(shot.hitRight && near(shot.x, 20 * 16 - 3), "body: nor does a 500 px/tick sideways shot");
        // ceiling and jump
        Body2D hero = new Body2D(200, 15 * 16 - 8, 6, 8);
        hero.gravity = 0.3f;
        hero.update(map);
        hero.vy = -6f;
        float top = hero.y;
        for (int i = 0; i < 100; i++) { hero.update(map); if (hero.y < top) top = hero.y; }
        check(top < 15 * 16 - 8 - 40 && hero.onGround, "body: jump goes up and comes back down (apex " + (15 * 16 - 8 - top) + ")");
        // one-way platform: land on it from above, pass from below, drop through
        Body2D op = new Body2D(7 * 16 + 8, 11 * 16 + 30, 5, 8);   // below the platform
        op.gravity = 0.3f;
        op.vy = -8f;
        for (int i = 0; i < 25; i++) op.update(map);
        check(op.y < 11 * 16 && !op.onGround || op.y < 11 * 16 - 8 + 1, "one-way: can be passed from below (y = " + op.y + ")");
        for (int i = 0; i < 200; i++) op.update(map);
        check(op.onGround && near(op.y, 11 * 16 - 8), "one-way: lands on top (y = " + op.y + ")");
        op.dropThrough = true;
        for (int i = 0; i < 60; i++) op.update(map);
        check(op.y > 11 * 16 + 8, "one-way: dropThrough falls through (y = " + op.y + ")");
        // flags under the body
        Body2D sw = new Body2D(12 * 16 + 8, 14 * 16 + 8, 6, 6);
        check(sw.touchesFlag(map, TileMap.WATER) && !b.touchesFlag(map, TileMap.WATER), "body: touchesFlag (water)");
        // top-down: no gravity, no ground
        Body2D td = new Body2D(100, 100, 5, 5);
        td.vx = 1; td.vy = 1;
        td.update(map);
        check(!td.onGround && near(td.x, 101f) && near(td.y, 101f), "body: top-down movement");
        Body2D prev = new Body2D(0, 0, 1, 1);
        prev.vx = 4;
        prev.update(map);
        check(near(prev.getRenderX(0.5f), 2f) && near(prev.getRenderX(1f), prev.x), "body: render interpolation");
        check(hero.overlaps(new Body2D(hero.x + 5, hero.y, 6, 8)) && !hero.overlaps(new Body2D(hero.x + 50, hero.y, 6, 8)), "body: overlaps");

        // ---- Collision2D
        check(Collision2D.overlaps(0, 0, 2, 2, 3, 0, 2, 2) && !Collision2D.overlaps(0, 0, 2, 2, 5, 0, 2, 2), "collision2d: boxes");
        check(Collision2D.circles(0, 0, 2, 3, 0, 2) && !Collision2D.circles(0, 0, 1, 3, 0, 1), "collision2d: circles");
        check(Collision2D.circleBox(5, 0, 1.2f, 3, 0, 2, 2) && !Collision2D.circleBox(6, 0, 0.9f, 3, 0, 2, 2) && Collision2D.circleBox(4.5f, 3.5f, 1f, 3, 0, 2, 2) == false,
                "collision2d: circle vs box, corner");
        check(Collision2D.pointInBox(1, 1, 0, 0, 2, 2) && !Collision2D.pointInBox(3, 0, 0, 0, 2, 2), "collision2d: point in box");

        // ---- rays
        Hit2D hit = new Hit2D();
        check(map.raycast(40, 100, 40, 300, hit) && hit.tileY == 15 && near(hit.y, 240f) && hit.ny == -1f && hit.nx == 0f, "raycast: down onto the floor (y = " + hit.y + ")");
        check(map.raycast(40, 100, 400, 100, hit) && hit.tileX == 20 && near(hit.x, 320f) && hit.nx == -1f, "raycast: sideways onto the wall");
        check(!map.raycast(40, 100, 200, 100, hit) && map.lineOfSight(40, 100, 200, 100, hit), "raycast: free line of sight");
        check(map.raycast(400, 100, 100, 100, hit) && hit.tileX == 20 && hit.nx == 1f, "raycast: leftwards from behind the wall");
        check(map.raycast(40, 300, 40, 100, hit) == true && hit.t == 0f || map.raycast(40, 250, 40, 100, hit), "raycast: starting inside a solid tile hits at once");
        check(map.raycast(10, 10, 300, 300, hit) && hit.t > 0f && hit.t < 1f, "raycast: diagonal");

        // ---- camera
        Camera2D cam = new Camera2D(480, 320);
        Mat4 proj = new Mat4();
        cam.setBounds(0, 0, 480, 320);
        cam.setPosition(0, 0);
        check(near(cam.getX(), 240) && near(cam.getY(), 160), "camera: bounds the size of the view centre it");
        cam.setBounds(0, 0, 1000, 800);
        cam.setPosition(0, 0);
        check(near(cam.getX(), 240) && near(cam.getY(), 160), "camera: clamped to the level");
        cam.setPosition(5000, 5000);
        check(near(cam.getX(), 760) && near(cam.getY(), 640), "camera: clamped at the far corner");
        cam.setPosition(500, 400);
        for (int i = 0; i < 200; i++) { cam.update(); cam.follow(700, 500, 0.1f); }
        check(near(cam.getX(), 700) && near(cam.getY(), 500), "camera: smooth follow converges");
        cam.setPosition(400, 300);
        cam.update();
        cam.follow(500, 300, 1f);
        cam.getProjection(proj, 0f);
        float l0 = cam.getLeft();
        cam.getProjection(proj, 1f);
        check(near(cam.getLeft() - l0, 100f) && near(cam.getRight() - cam.getLeft(), 480f), "camera: render interpolation (alpha)");
        cam.setZoom(2f);
        cam.getProjection(proj, 1f);
        check(near(cam.getRight() - cam.getLeft(), 240f) && near(cam.getBottom() - cam.getTop(), 160f), "camera: zoom 2 shows half the world");
        cam.setZoom(1f);
        cam.setPosition(300.4f, 300.4f);
        cam.getProjection(proj, 1f);
        check(near(cam.getLeft(), (float) Math.floor(cam.getLeft() + 0.0001f)), "camera: pixel snapping gives whole pixel positions (" + cam.getLeft() + ")");
        cam.setPixelSnap(false);
        cam.setPosition(300.4f, 300.4f);
        cam.getProjection(proj, 1f);
        check(!near(cam.getLeft(), (float) Math.floor(cam.getLeft())), "camera: without it the position is exact");
        cam.setPixelSnap(true);
        cam.shake(10f, 20);
        float maxOff = 0;
        for (int i = 0; i < 20; i++) { cam.update(); cam.getProjection(proj, 1f); float o = Math.abs(cam.getLeft() - (300 - 240)); if (o > maxOff) maxOff = o; }
        cam.update();
        cam.getProjection(proj, 1f);
        check(maxOff > 1f && maxOff <= 11f && near(cam.getLeft(), 60f), "camera: shake moves the view and ends (max offset " + maxOff + ")");
        check(near(cam.screenToWorldX(240, 480), cam.getLeft() + 240f) && near(cam.worldToScreenX(cam.getLeft() + 120f, 480), 120f)
                && cam.isVisible(cam.getLeft() + 10, cam.getTop() + 10, 5, 5) && !cam.isVisible(cam.getRight() + 10, 0, 5, 5), "camera: coordinate conversion and visibility");

        // ---- drawing: culling
        cam.setBounds(0, 0, 480, 320);
        cam.setPosition(240, 160);
        cam.getProjection(proj, 1f);
        rec.reset();
        batch.begin(proj);
        map.drawLayer(batch, cam, 1);
        batch.end();
        check(rec.quads == 31 * 21 || rec.quads == 30 * 20, "map: draws only the visible tiles of a full layer (" + rec.quads + " of 600)");
        cam.setBounds(0, 0, 4000, 4000);
        cam.setPosition(2000, 2000);
        cam.getProjection(proj, 1f);
        rec.reset();
        batch.begin(proj);
        map.draw(batch, cam);
        batch.end();
        check(rec.quads == 0, "map: nothing is drawn when the camera is far outside");
        map.setLayerVisible(1, false);
        cam.setPosition(240, 160);
        cam.setBounds(0, 0, 480, 320);
        cam.getProjection(proj, 1f);
        rec.reset();
        batch.begin(proj);
        map.draw(batch, cam);
        batch.end();
        int withoutL1 = rec.quads;
        map.setLayerVisible(1, true);
        rec.reset();
        batch.begin(proj);
        map.draw(batch, cam);
        batch.end();
        check(rec.quads == withoutL1 + 30 * 20 || rec.quads > withoutL1 + 500, "map: hidden layers are skipped (" + withoutL1 + " -> " + rec.quads + ")");
        // parallax: a layer at 0.5 starts further left in the world when the camera is away from the origin
        map.setParallax(1, 0.5f, 0.5f);
        cam.setBounds(0, 0, 4000, 4000);
        cam.setPosition(600, 160);
        cam.getProjection(proj, 1f);
        rec.reset();
        batch.begin(proj);
        map.drawLayer(batch, cam, 1);
        batch.end();
        check(rec.quads > 0 && rec.minX < cam.getRight() && rec.maxX >= cam.getLeft(), "map: parallax layer is visible where the camera is (" + rec.quads + " quads)");

        // ---- parallax background
        ParallaxLayer pl = new ParallaxLayer(set.get(1), 100, 50, 0.5f, 0f, 10f, true);
        cam.setPosition(1000, 160);
        cam.getProjection(proj, 1f);
        rec.reset();
        batch.begin(proj);
        pl.draw(batch, cam);
        batch.end();
        check(rec.quads >= 5 && rec.quads <= 6 && rec.minX <= cam.getLeft() + 0.01f && rec.maxX >= cam.getRight() - 0.01f, "parallax layer: repeats to cover the screen (" + rec.quads + " tiles)");

        // ---- zero allocation
        Body2D z = new Body2D(40, 100, 6, 8);
        z.gravity = 0.3f;
        for (int i = 0; i < 500; i++) { z.vx = 1.5f; z.update(map); cam.update(); cam.follow(z.x, z.y, 0.1f); set.update(); cam.getProjection(proj, 0.5f);
            batch.begin(proj); map.draw(batch, cam); batch.end(); map.raycast(0, 0, 300, 200, hit); }
        allocated(); allocated();
        long a0 = allocated();
        for (int i = 0; i < 5000; i++) {
            z.vx = (i & 255) < 128 ? 1.5f : -1.5f;
            if (z.onGround && (i & 31) == 0) z.vy = -5f;
            z.update(map);
            if ((i & 511) == 0) cam.shake(4f, 20);
            cam.update();
            cam.follow(z.x, z.y, 0.1f);
            set.update();
            cam.getProjection(proj, 0.5f);
            batch.begin(proj);
            pl.draw(batch, cam);
            map.draw(batch, cam);
            batch.end();
            map.raycast(z.x, z.y, z.x + 100, z.y - 40, hit);
            z.touchesFlag(map, TileMap.WATER);
        }
        long d = allocated() - a0;
        check(d < 1024, "zero allocation: 5000 ticks of bodies, camera, culled drawing, rays (" + d + " bytes)");

        System.out.println(failed == 0 ? "ALL 2D TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
