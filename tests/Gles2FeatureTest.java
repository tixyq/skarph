// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.android.*;
import skarph.core.*;

/** The renderer features on the fake GL of Gles2Test: blending, alpha test, tint, lines, scissor, viewport, dynamic meshes. */
public class Gles2FeatureTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public static void main(String[] args) {
        Gles2Test.Rig rig = new Gles2Test.Rig().start();
        Gles2Test.FakeGl gl = rig.gl;
        GLES2Renderer r = rig.r;
        Mat4 proj = new Mat4().setPerspective(50f, 1.6f, 0.5f, 100f);
        Mat4 view = new Mat4().setLookAt(3, 4, 9, 0, 1, 0, 0, 1, 0);
        Mat4 model = new Mat4();
        r.setCamera(proj, view);
        int quad = r.createMesh(Gles2Test.texturedQuad());

        r.drawMesh(quad, model);
        Gles2Test.Draw d0 = rig.last();
        check(!d0.blend && d0.depthMask, "opaque: no blending, depth writes on");
        int f0 = gl.featuresOf(d0.program);

        r.setBlendMode(Renderer.BLEND_ALPHA);
        r.drawMesh(quad, model);
        Gles2Test.Draw d1 = rig.last();
        check(d1.blend && d1.blendSrc == Gl2.GL_SRC_ALPHA && d1.blendDst == Gl2.GL_ONE_MINUS_SRC_ALPHA && !d1.depthMask, "alpha blend: SRC_ALPHA / ONE_MINUS_SRC_ALPHA, depth writes off");
        r.setBlendMode(Renderer.BLEND_ADD);
        r.drawMesh(quad, model);
        Gles2Test.Draw d2 = rig.last();
        check(d2.blend && d2.blendSrc == Gl2.GL_SRC_ALPHA && d2.blendDst == Gl2.GL_ONE && !d2.depthMask, "additive blend: SRC_ALPHA / ONE");
        r.setBlendMode(Renderer.BLEND_OPAQUE);
        r.drawMesh(quad, model);
        check(!rig.last().blend && rig.last().depthMask, "back to opaque restores the state");

        r.setTint(1f, 0.5f, 0.25f, 0.8f);
        r.drawMesh(quad, model);
        Gles2Test.Draw d3 = rig.last();
        String src = gl.programSource(d3.program);
        float[] tint = gl.uniform(d3.program, "uTint");
        check(src.contains("#define TINT") && tint != null && tint[1] == 0.5f && tint[3] == 0.8f, "tint: TINT variant of the shader and uTint set");
        r.setTint(1f, 1f, 1f, 1f);
        r.drawMesh(quad, model);
        check(!gl.programSource(rig.last().program).contains("#define TINT"), "tint off: the plain variant is used again");

        r.setAlphaTest(0.5f);
        r.drawMesh(quad, model);
        Gles2Test.Draw d4 = rig.last();
        float[] cut = gl.uniform(d4.program, "uCutoff");
        check(gl.programSource(d4.program).contains("#define ATEST") && cut != null && cut[0] == 0.5f, "alpha test: ATEST variant and uCutoff");
        r.setAlphaTest(0f);

        int before = gl.draws.size();
        r.setCulling(false);
        r.drawMesh(quad, model);
        check(!rig.last().cull, "double sided: culling off");
        r.setCulling(true);
        r.drawMesh(quad, model);
        check(rig.last().cull, "culling back on");

        // the same mesh again: no buffer / texture rebinding
        r.setBlendMode(Renderer.BLEND_OPAQUE);
        r.drawMesh(quad, model);
        int bb = gl.bindBufferCalls, bt = gl.bindTextureCalls;
        r.drawMesh(quad, model);
        r.drawMesh(quad, model);
        check(gl.bindBufferCalls == bb && gl.bindTextureCalls == bt, "state cache: drawing the same mesh again binds nothing");
        int other = r.createMesh(Gles2Test.texturedQuad());
        r.drawMesh(other, model);
        check(gl.bindBufferCalls > bb && rig.last().arrayBuf != 0, "state cache: another mesh binds its buffers");
        r.drawMesh(quad, model);
        check(rig.last().arrayBuf != 0 && rig.last().elemBuf != 0, "state cache: switching back rebinds the right buffers");
        float[] ln = new float[2 * Renderer.LINE_FLOATS];
        r.drawLines(ln, 2);
        r.drawMesh(quad, model);
        check(rig.last().arrayBuf != 0, "state cache: after lines (client memory) the VBO is bound again");

        // lines
        float[] lines = new float[4 * Renderer.LINE_FLOATS];
        for (int i = 0; i < 4; i++) { lines[i * 7 + 3] = 1; lines[i * 7 + 6] = 1; lines[i * 7] = i; }
        r.drawLines(lines, 4);
        check(gl.arrayDraws == 2 && gl.lastArrayMode == Gl2.GL_LINES && gl.lastArrayCount == 4, "lines: one glDrawArrays(GL_LINES, 4 vertices)");
        r.drawLines(lines, 3);
        check(gl.lastArrayCount == 2, "lines: an odd vertex count is rounded down to whole lines");
        int ad = gl.arrayDraws;
        r.drawLines(lines, 1);
        check(gl.arrayDraws == ad, "lines: fewer than 2 vertices draw nothing");

        // scissor and viewport (y is from the top)
        r.setScissor(true, 10, 20, 100, 50);
        check(gl.caps.contains(Gl2.GL_SCISSOR_TEST) && gl.scissors.get(gl.scissors.size() - 1)[1] == 480 - 70, "scissor: enabled, y flipped to the GL origin");
        r.setScissor(false, 0, 0, 0, 0);
        check(!gl.caps.contains(Gl2.GL_SCISSOR_TEST), "scissor: disabled");
        r.setViewport(0, 0, 400, 240);
        r.resetViewport();
        r.clearDepth();
        check(gl.clears >= 1, "clearDepth clears the depth buffer");

        // dynamic meshes
        int dm = r.createDynamicMesh(8, 12, false, false);
        check(dm >= 0 && dm != quad, "dynamic mesh: created");
        float[] pos = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] col = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        short[] idx = {0, 1, 2, 0, 2, 3};
        int bd = gl.bufferDataCalls;
        r.updateMesh(dm, pos, null, col, null, idx, 4, 6);
        r.drawMesh(dm, model);
        check(gl.bufferDataCalls > bd && rig.last().count == 6, "dynamic mesh: update uploads, the draw has 6 indices");
        r.updateMesh(dm, pos, null, col, null, idx, 4, 3);
        r.drawMesh(dm, model);
        check(rig.last().count == 3, "dynamic mesh: the index count follows the update");
        check(r.createDynamicMesh(70000, 12, false, false) == -1, "dynamic mesh: too many vertices are refused");
        r.updateMesh(quad, pos, null, col, null, idx, 4, 6);       // not a dynamic mesh: ignored, no crash
        r.deleteMesh(dm);
        check(rig.log.errors.isEmpty() && !r.hasFailed(), "no errors logged");

        // zero allocation of the new state changes
        GLES2Renderer qr = null;
        Gles2Test.QuietGl qgl = new Gles2Test.QuietGl();
        Engine eng = new Engine();
        qr = new GLES2Renderer(eng, qgl, new Gles2Test.FakeImages(), new Gles2Test.TestLog(), new GLES2Renderer.FailureListener() { public void onFailure(String s) { } });
        qr.onSurfaceCreated();
        qr.onSurfaceChanged(800, 480);
        qr.setCamera(proj, view);
        int qm = qr.createMesh(Gles2Test.texturedQuad());
        int qd = qr.createDynamicMesh(8, 12, false, false);
        for (int i = 0; i < 10000; i++) {          // warm-up: compiles the lazily built shader variants
            qr.setBlendMode(i % 3);
            qr.setTint(1, 1, 1, (i & 1) == 0 ? 0.5f : 1f);
            qr.setAlphaTest((i & 2) == 0 ? 0.5f : 0f);
            qr.drawMesh(qm, model);
            qr.drawLines(lines, 4);
            qr.updateMesh(qd, pos, null, col, null, idx, 4, 6);
            qr.drawMesh(qd, model);
            qr.setScissor(true, 1, 1, 10, 10);
            qr.setScissor(false, 0, 0, 0, 0);
            qr.setViewport(0, 0, 100, 100);
            qr.resetViewport();
        }
        allocated(); allocated();
        long a0 = allocated();
        for (int i = 0; i < 5000; i++) {
            qr.setBlendMode(i % 3);
            qr.setTint(1, 1, 1, (i & 1) == 0 ? 0.5f : 1f);
            qr.setAlphaTest((i & 2) == 0 ? 0.5f : 0f);
            qr.drawMesh(qm, model);
            qr.drawLines(lines, 4);
            qr.updateMesh(qd, pos, null, col, null, idx, 4, 6);
            qr.drawMesh(qd, model);
            qr.setScissor(true, 1, 1, 10, 10);
            qr.setScissor(false, 0, 0, 0, 0);
            qr.setViewport(0, 0, 100, 100);
            qr.resetViewport();
        }
        long d = allocated() - a0;
        check(d < 2048, "zero allocation: 5000 frames of blend / tint / lines / dynamic mesh / scissor (" + d + " bytes)");

        System.out.println(failed == 0 ? "ALL GLES2 FEATURE TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
