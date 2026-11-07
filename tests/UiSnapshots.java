// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Test tool: draws the demo screens with a tiny CPU renderer (2D quads only) and writes PNG files,
 * so the layout of the UI can be inspected without a phone.
 * usage: UiSnapshots <assets dir> <output dir>
 */
public class UiSnapshots {
    static final class SoftRenderer implements Renderer {
        final File dir;
        final int w, h;
        final BufferedImage img;
        final BufferedImage[] tex = new BufferedImage[16];
        int texCount;
        final Mat4 proj = new Mat4();
        float cr = 0, cg = 0, cb = 0;

        SoftRenderer(String assetsDir, int w, int h) {
            this.dir = new File(assetsDir); this.w = w; this.h = h;
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        public String getBackendName() { return "test"; }
        public int getWidth() { return w; }
        public int getHeight() { return h; }
        public int getDrawCalls() { return 0; }
        public int getTriangles() { return 0; }
        public void setFog(boolean enabled, float start, float end, float r, float g, float b) { }
        public void drawSky(int mesh) { }
        public void drawBillboards(int texture, float[] data, int count, boolean additive) { }
        public int createMesh(MeshData d) { return 0; }
        public int loadTexture(String p, boolean s) { return loadTexture(p, s, false); }
        public int loadTexture(String p, boolean s, boolean r) {
            try { tex[texCount] = ImageIO.read(new File(dir, p)); return texCount++; }
            catch (Exception e) { return -1; }
        }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return true; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return tex[t].getWidth(); }
        public int getTextureHeight(int t) { return tex[t].getHeight(); }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean e) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float a, float b, float c, float d, float e, float f) { }
        public void clear(float r, float g, float b) {
            int rgb = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        }
        public void setCamera(Mat4 p, Mat4 v) { proj.set(p); }
        public void drawMesh(int m, Mat4 model) { }

        public void drawQuads(int t, float[] v, int n) {
            BufferedImage tx = tex[t];
            for (int q = 0; q < n; q++) {
                int o = q * 32;
                tri(tx, v, o, o + 8, o + 16);
                tri(tx, v, o, o + 16, o + 24);
            }
        }
        float sx(float x) { return (proj.m[0] * x + proj.m[12] + 1f) * 0.5f * w; }
        float sy(float y) { return (1f - (proj.m[5] * y + proj.m[13])) * 0.5f * h; }

        void tri(BufferedImage tx, float[] v, int a, int b, int c) {
            float ax = sx(v[a]), ay = sy(v[a + 1]), bx = sx(v[b]), by = sy(v[b + 1]), cx = sx(v[c]), cy = sy(v[c + 1]);
            float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
            if (Math.abs(area) < 1e-6f) return;
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
            int tw = tx.getWidth(), th = tx.getHeight();
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float px = x + 0.5013f, py = y + 0.5013f; // avoid exact ties on rectangle edges
                float w0 = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area;
                float w1 = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area;
                float w2 = 1f - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float u = w0 * v[a + 2] + w1 * v[b + 2] + w2 * v[c + 2];
                float vv = w0 * v[a + 3] + w1 * v[b + 3] + w2 * v[c + 3];
                int tx0 = Math.min(tw - 1, Math.max(0, (int) (u * tw))), ty0 = Math.min(th - 1, Math.max(0, (int) (vv * th)));
                int argb = tx.getRGB(tx0, ty0);
                float ta = ((argb >>> 24) & 255) / 255f * (w0 * v[a + 7] + w1 * v[b + 7] + w2 * v[c + 7]);
                if (ta <= 0f) continue;
                float tr = ((argb >> 16) & 255) / 255f * (w0 * v[a + 4] + w1 * v[b + 4] + w2 * v[c + 4]);
                float tg = ((argb >> 8) & 255) / 255f * (w0 * v[a + 5] + w1 * v[b + 5] + w2 * v[c + 5]);
                float tb = (argb & 255) / 255f * (w0 * v[a + 6] + w1 * v[b + 6] + w2 * v[c + 6]);
                int dst = img.getRGB(x, y);
                float dr = ((dst >> 16) & 255) / 255f, dg = ((dst >> 8) & 255) / 255f, db = (dst & 255) / 255f;
                int rr = (int) ((tr * ta + dr * (1 - ta)) * 255 + 0.5f), gg = (int) ((tg * ta + dg * (1 - ta)) * 255 + 0.5f), bb = (int) ((tb * ta + db * (1 - ta)) * 255 + 0.5f);
                img.setRGB(x, y, (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb));
            }
        }
    }

    static void play(Engine e, SoftRenderer r, Screen s, int[][] script, int frames, String out) throws Exception {
        e.setScreen(s);
        long t = 1000000000L;
        for (int f = 0; f <= frames; f++) {
            t += 16666667L;
            for (int[] ev : script) if (ev[0] == f) e.input.push(ev[1], ev.length > 4 ? ev[4] : 0, ev[2], ev[3]);
            e.frame(t, r);
        }
        ImageIO.write(r.img, "png", new File(out));
        System.out.println("wrote " + out);
    }

    public static void main(String[] args) throws Exception {
        String assets = args[0];
        String outDir = args[1];
        new File(outDir).mkdirs();
        int D = InputEvent.DOWN, U = InputEvent.UP, M = InputEvent.MOVE;
        int W = 800, H = 480;
        // menu
        Engine e1 = new Engine(); e1.setAssets(new ContentTest.FileAssets(assets));
        play(e1, new SoftRenderer(assets, W, H), new skarph.demo.v06.MenuDemoScreen(), new int[][] {}, 90, outDir + "/ui-menu.png");
        // settings
        Engine e2 = new Engine(); e2.setAssets(new ContentTest.FileAssets(assets));
        play(e2, new SoftRenderer(assets, W, H), new skarph.demo.v06.MenuDemoScreen(),
                new int[][] {{30, D, 400, 315}, {31, U, 400, 315}, {40, D, 250, 177}, {41, U, 250, 177}, {45, D, 527, 333}, {46, U, 527, 333}}, 90, outDir + "/ui-settings.png");
        // touch lab: coins in the air and three fingers held down
        Engine e3 = new Engine(); e3.setAssets(new ContentTest.FileAssets(assets));
        play(e3, new SoftRenderer(assets, W, H), new skarph.demo.v06.MenuDemoScreen(),
                new int[][] {{20, D, 400, 225}, {21, U, 400, 225}, {40, D, 300, 380}, {41, U, 300, 380}, {46, D, 560, 380}, {47, U, 560, 380},
                        {52, D, 200, 250, 0}, {52, D, 420, 300, 1}, {52, D, 640, 250, 2}}, 62, outDir + "/ui-touch.png");
    }
}
