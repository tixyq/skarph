// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import java.io.*;
import java.nio.file.*;
import skarph.core.*;

/** Proportional fonts, wrapping, SpriteBatch scissor / nine-patch, Strings, and the Ui widgets. */
public class TextUiTest {
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

    static final class Rec implements Renderer {
        int quads, calls, scissorOn, scissorOff;
        float maxX, minX;
        public String getBackendName() { return "t"; }
        public int getWidth() { return 480; }
        public int getHeight() { return 320; }
        public int getDrawCalls() { return 0; }
        public int getTriangles() { return 0; }
        public void setFog(boolean e, float s, float en, float r, float g, float b) { }
        public void drawSky(int m) { }
        public void drawBillboards(int t, float[] d, int n, boolean a) { }
        public int createMesh(MeshData d) { return 0; }
        public int loadTexture(String p, boolean s) { return 0; }
        public int loadTexture(String p, boolean s, boolean r) { return 0; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return false; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return 512; }
        public int getTextureHeight(int t) { return 256; }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean on) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) { }
        public void clear(float r, float g, float b) { }
        public void setCamera(Mat4 p, Mat4 v) { }
        public void drawMesh(int m, Mat4 model) { }
        public void drawQuads(int tex, float[] v, int n) {
            calls++; quads += n;
            for (int q = 0; q < n; q++) {
                float x0 = v[q * 32], x1 = v[q * 32 + 8];
                if (x1 > maxX) maxX = x1;
                if (x0 < minX) minX = x0;
            }
        }
        public void setScissor(boolean e, int x, int y, int w, int h) { if (e) scissorOn++; else scissorOff++; }
        void reset() { quads = 0; calls = 0; maxX = 0; minX = 1e9f; scissorOn = scissorOff = 0; }
    }

    static final class L implements Ui.Listener {
        int clicks, submits, lastId = -1;
        public void onClick(int id) { clicks++; lastId = id; }
        public void onValueChanged(int id, float v) { }
        public void onSubmit(int id) { submits++; }
    }

    static InputEvent ev(int type, float x, float y) {
        InputEvent e = new InputEvent();
        e.type = type; e.pointer = 0; e.x = x; e.y = y;
        return e;
    }

    static void tap(Ui ui, float x, float y) {
        ui.handle(ev(InputEvent.DOWN, x, y));
        ui.handle(ev(InputEvent.UP, x, y));
    }

    public static void main(String[] args) throws Exception {
        byte[] fnt = Files.readAllBytes(Paths.get("fonts/dejavu16.fnt"));
        BitmapFont f = BitmapFont.fromData(1, 512, 256, fnt);
        check(f != null && f.isProportional(), "font: dejavu16.fnt loads");
        check(BitmapFont.fromData(1, 512, 256, new byte[] {1, 2, 3}) == null, "font: garbage is rejected (null)");
        check(f.hasGlyph('A') && f.hasGlyph('Ж') && f.hasGlyph('ё') && f.hasGlyph('é') && !f.hasGlyph('中'),
                "font: Latin, Cyrillic and Latin-1 are there, CJK is not");
        check(f.getCharWidth('i') < f.getCharWidth('W') - 3f, "font: proportional widths (i < W)");
        float w = f.getTextWidth("Привет");
        float sum = 0;
        for (char c : "Привет".toCharArray()) sum += f.getCharWidth(c);
        check(w > 20f && near(w, sum), "font: Cyrillic text width = sum of the characters (" + w + ")");
        f.setScale(2f);
        check(near(f.getTextWidth("Hello"), 2f * sumOf(f, 1f, "Hello")), "font: scale applies to measuring");
        f.setScale(1f);
        check(near(f.getTextWidth("ab\nabcd"), f.getTextWidth("abcd")), "font: width of a multi-line text is the widest line");
        TextBuffer tb = new TextBuffer(64);
        tb.append("Hello");
        check(near(f.getTextWidth(tb), f.getTextWidth("Hello")), "font: String and TextBuffer agree");

        // wrapping
        float ww = f.getTextWidth("hello world");
        check(f.countLines("hello world foo", ww + 0.5f) == 2, "wrap: breaks at the last space that fits");
        check(f.countLines("hello world", ww + 0.5f) == 1, "wrap: fits in one line");
        check(f.countLines("a\nb\nc", 1000f) == 3, "wrap: newlines");
        check(f.countLines("supercalifragilisticexpialidocious", 60f) > 2, "wrap: a long word is broken");
        check(f.countLines("", 100f) == 1, "wrap: empty text is one line");
        check(f.countLines("a   b", 1000f) == 1, "wrap: spaces do not make lines");
        Rec rec = new Rec();
        SpriteBatch sb = new SpriteBatch(rec, 512);
        Mat4 proj = new Mat4().setOrtho(0, 480, 320, 0, -1, 1);
        rec.reset();
        sb.begin(proj);
        int lines = f.drawWrapped(sb, "The quick brown fox jumps over the lazy dog", 10, 10, 120, BitmapFont.ALIGN_LEFT, 0);
        sb.end();
        check(lines >= 3 && rec.quads == 35 && rec.maxX <= 10 + 120 + 1f, "wrap: drawn in a column (" + lines + " lines, " + rec.quads + " glyphs, right edge " + rec.maxX + ")");
        rec.reset();
        sb.begin(proj);
        lines = f.drawWrapped(sb, "The quick brown fox jumps over the lazy dog", 10, 10, 120, BitmapFont.ALIGN_LEFT, 2);
        sb.end();
        check(lines == 2, "wrap: maxLines");
        rec.reset();
        sb.begin(proj);
        f.drawAligned(sb, "abc", 100, 0, 100, BitmapFont.ALIGN_RIGHT);
        sb.end();
        check(rec.maxX > 195f && rec.maxX <= 200.01f, "align: right (" + rec.maxX + ")");
        rec.reset();
        sb.begin(proj);
        f.drawEllipsized(sb, "A very long text that does not fit", 0, 0, 80);
        sb.end();
        check(rec.maxX <= 80.5f && rec.quads > 5, "ellipsis: cut to the width (" + rec.maxX + ")");
        rec.reset();
        sb.begin(proj);
        f.drawEllipsized(sb, "short", 0, 0, 80);
        sb.end();
        check(rec.quads == 5, "ellipsis: short text is untouched");

        // unknown character falls back to '?'
        rec.reset();
        sb.begin(proj);
        f.draw(sb, "中", 0, 0);
        sb.end();
        check(rec.quads == 1, "font: a missing character is drawn as '?'");

        // legacy fixed width font still works
        BitmapFont fixed = new BitmapFont(TextureRegion.grid(1, 128, 128, 8, 16, 16, 6), 32);
        check(near(fixed.getTextWidth("abc"), 24f) && !fixed.isProportional() && near(fixed.getTextWidth(3), 24f), "font: fixed width fonts keep working");

        // SpriteBatch: nine patch, tiled, scissor
        TextureRegion panel = new TextureRegion().set(2, 64, 64, 0, 0, 32, 32);
        rec.reset();
        sb.begin(proj);
        sb.drawNine(panel, 10, 10, 100, 60, 8, 8, 8, 8, 1f);
        sb.end();
        check(rec.quads == 9 && near(rec.maxX, 110f), "nine-patch: 9 quads covering the rectangle");
        rec.reset();
        sb.begin(proj);
        sb.drawNine(panel, 0, 0, 10, 10, 8, 8, 8, 8, 1f);   // smaller than the borders
        sb.end();
        check(rec.maxX <= 10.001f, "nine-patch: borders shrink in a tiny rectangle");
        rec.reset();
        sb.begin(proj);
        sb.drawTiled(panel, 0, 0, 80, 40, 1f);
        sb.end();
        check(rec.quads == 6 && near(rec.maxX, 80f), "tiled: 3 x 2 tiles, the last cut (" + rec.quads + ")");
        rec.reset();
        sb.begin(proj);
        sb.setScissor(0, 0, 100, 100);
        sb.draw(panel, 0, 0);
        sb.clearScissor();
        sb.end();
        check(rec.scissorOn == 1 && rec.scissorOff == 1 && rec.calls == 1, "scissor: reaches the renderer, pending sprites are flushed first");

        // Strings
        Strings tr = new Strings(8);
        String file = "﻿# comment\r\nhello=Привет\r\nscore=Счёт: {0}, x{1}\nmulti=a\\nb\nname=Hi {0}\n";
        int n = tr.load(file.getBytes("UTF-8"));
        check(n == 4 && tr.get("hello").equals("Привет") && tr.get("multi").equals("a\nb"), "strings: UTF-8, BOM, comments, escapes");
        check(tr.get("nope").equals("nope") && !tr.has("nope") && tr.has("hello"), "strings: a missing key returns the key");
        TextBuffer out = new TextBuffer(64);
        tr.format(out, "score", 1200, 3);
        check(out.toString().equals("Счёт: 1200, x3"), "strings: format with numbers (" + out + ")");
        out.clear();
        tr.format(out, "name", "Bob");
        check(out.toString().equals("Hi Bob"), "strings: format with a string");
        Strings big = new Strings(2);
        for (int i = 0; i < 100; i++) big.put("k" + i, "v" + i);
        check(big.size() == 100 && big.get("k77").equals("v77"), "strings: the table grows");
        for (int i = 0; i < 20000; i++) { out.clear(); tr.format(out, "score", i, 2); tr.get("hello"); }
        allocated(); allocated();
        long s0 = allocated();
        for (int i = 0; i < 10000; i++) { out.clear(); tr.format(out, "score", i, 2); tr.get("hello"); }
        long sd = allocated() - s0; check(sd < 1024, "strings: lookup and format do not allocate (" + sd + ")");

        // ---- Ui
        rec.reset();
        TextureRegion white = new TextureRegion().set(2, 64, 64, 0, 0, 4, 4);
        Ui ui = new Ui(32, 64, sb, f, white);
        ui.setViewport(480, 320, 480, 320);
        L lis = new L();
        ui.setListener(lis);
        int field = ui.addTextField("", 10, 10, 200, 24, Ui.LEFT, Ui.TOP, 1f);
        int kb = ui.addKeyboard(0, 0, 480, 150, Ui.LEFT, Ui.BOTTOM, 1f);
        ui.bindKeyboard(kb);
        check(!ui.isShown(kb), "ui: a bound keyboard is hidden at first");
        tap(ui, 50, 20);
        check(ui.getFocus() == field && ui.isShown(kb), "ui: touching a text field focuses it and shows the keyboard");
        ui.typeChar('a');
        ui.typeChar('b');
        ui.backspace();
        check(ui.text(field).toString().equals("a"), "ui: typeChar / backspace");
        float kx = ui.getRectX(kb), ky = ui.getRectY(kb), kw = ui.getRectW(kb), kh = ui.getRectH(kb);
        float unit = kw / 12f, row = kh / 5f;
        // row 1 ("qwertyuiop"): 10 units centred
        float q0 = kx + (kw - 10 * unit) / 2f;
        tap(ui, q0 + unit * 0.5f, ky + row * 1.5f);
        check(ui.text(field).toString().equals("aq"), "ui keyboard: 'q' key");
        tap(ui, kx + 1.5f * unit * 0.5f + (kw - 12f * unit) * 0.5f, ky + row * 3.5f);  // shift: row 3 starts at (kw - 10u)/2: 7 keys + 3 units = 10
        float r3 = kx + (kw - 10f * unit) * 0.5f;
        tap(ui, r3 + 0.75f * unit, ky + row * 3.5f);
        tap(ui, q0 + unit * 0.5f, ky + row * 1.5f);
        check(ui.text(field).toString().equals("aqQ") || ui.text(field).toString().equals("aqq"), "ui keyboard: shift then a letter (" + ui.text(field) + ")");
        // language switch (bottom row, first key) then a Cyrillic letter
        tap(ui, kx + 0.75f * unit, ky + row * 4.5f);
        ui.text(field).clear();
        tap(ui, kx + (kw - 12f * unit) * 0.5f + unit * 0.5f, ky + row * 1.5f);   // first key of the Russian row 1: 12 units
        check(ui.text(field).toString().equals("й"), "ui keyboard: Cyrillic layout types й (" + ui.text(field) + ")");
        tap(ui, kx + kw - 1.5f * unit, ky + row * 4.5f);                          // enter (last key of the bottom row)
        check(lis.submits == 1, "ui keyboard: enter submits");
        tap(ui, 300, 100);
        check(ui.getFocus() == -1 && !ui.isShown(kb), "ui: touching elsewhere removes the focus and hides the keyboard");

        // list
        int list = ui.addList(250, 10, 200, 100, Ui.LEFT, Ui.TOP, 50, 24, 20f, 1f);
        for (int i = 0; i < 30; i++) {
            ui.listText(list, ui.listAdd(list, "")).append("Item ").append(i);
        }
        check(ui.listCount(list) == 30, "list: items added");
        tap(ui, 300, 10 + 30);
        check(ui.getSelected(list) == 1 && lis.lastId == list, "list: tap selects the item under the finger");
        ui.handle(ev(InputEvent.DOWN, 300, 90));
        for (int i = 1; i <= 8; i++) ui.handle(ev(InputEvent.MOVE, 300, 90 - i * 8));
        ui.handle(ev(InputEvent.UP, 300, 26));
        int sel = ui.getSelected(list);
        check(sel == 1, "list: a drag scrolls and does not select");
        // scrolled: the first visible item changed
        ui.update();
        for (int i = 0; i < 120; i++) ui.update();
        tap(ui, 300, 12);
        check(ui.getSelected(list) > 3, "list: it scrolled with inertia (selected " + ui.getSelected(list) + " at the top)");
        ui.listScrollTo(list, 0);
        tap(ui, 300, 12);
        check(ui.getSelected(list) == 0, "list: scroll to an item");

        // label wrap and image, skin
        int lab = ui.addLabel("The quick brown fox jumps over the lazy dog", 10, 200, Ui.LEFT, Ui.TOP, 1f);
        ui.setWrap(lab, 100f, BitmapFont.ALIGN_CENTER);
        check(near(ui.getRectW(lab), 100f) && ui.getRectH(lab) >= 3 * f.getLineHeight() - 0.1f, "ui: wrapped label has a column size");
        ui.addImage(white, 10, 280, 32, 32, Ui.LEFT, Ui.TOP);
        int pan = ui.addPanel(100, 150, 100, 60, Ui.LEFT, Ui.TOP);
        int btn = ui.addButton("OK", 120, 220, 80, 24, Ui.LEFT, Ui.TOP, 1f);
        ui.setSkin(panel, panel, panel, 8, 1f);
        rec.reset();
        sb.begin(proj);
        ui.draw();
        sb.end();
        check(rec.quads > 50 && rec.scissorOn >= 1 && rec.scissorOn == rec.scissorOff, "ui: draw (" + rec.quads + " quads, scissors " + rec.scissorOn + "/" + rec.scissorOff + ")");

        // zero allocation with everything
        ui.setFocus(field);
        ui.setVisible(kb, true);
        long a0;
        for (int i = 0; i < 200; i++) { ui.update(); sb.begin(proj); ui.draw(); sb.end(); }
        InputEvent e1 = ev(InputEvent.DOWN, 0, 0);
        a0 = allocated();
        for (int i = 0; i < 3000; i++) {
            ui.update();
            e1.type = InputEvent.DOWN; e1.x = 300; e1.y = 80;
            ui.handle(e1);
            e1.type = InputEvent.MOVE; e1.y = 60 - (i % 30);
            ui.handle(e1);
            e1.type = InputEvent.UP;
            ui.handle(e1);
            ui.typeChar((char) ('a' + i % 26));
            if ((i & 7) == 0) ui.backspace();
            sb.begin(proj);
            ui.draw();
            sb.end();
        }
        long d = allocated() - a0;
        check(d < 1024, "zero allocation: 3000 frames of Ui with list, field, keyboard, wrapped label, skin (" + d + " bytes)");

        System.out.println(failed == 0 ? "ALL TEXT/UI TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }

    static float sumOf(BitmapFont f, float scale, String s) {
        float w = 0;
        float old = f.getScale();
        f.setScale(scale);
        for (char c : s.toCharArray()) w += f.getCharWidth(c);
        f.setScale(old);
        return w;
    }
}
