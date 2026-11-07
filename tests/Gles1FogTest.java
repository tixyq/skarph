// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.android.GLES1Renderer;
import skarph.core.Engine;
import skarph.core.Renderer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import javax.microedition.khronos.opengles.GL10;

/**
 * Fog, sky and billboards in the ES 1.x backend, on a recording GL10 (no GPU, no Android runtime). Needs android.jar on the class path
 * only for the GL10 interface. Only paths without android.util.Log are used (Log would throw in a plain JVM).
 */
public class Gles1FogTest {
    static int failed;
    static final ArrayList<String> calls = new ArrayList<String>();
    static final ArrayList<float[]> loads = new ArrayList<float[]>();   // every glLoadMatrixf argument, in order

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static int count(String prefix) {
        int n = 0;
        for (String c : calls) if (c.startsWith(prefix)) n++;
        return n;
    }

    static int last(String prefix) {
        for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).startsWith(prefix)) return i;
        return -1;
    }

    public static void main(String[] args) {
        GL10 gl = (GL10) Proxy.newProxyInstance(Gles1FogTest.class.getClassLoader(), new Class<?>[] {GL10.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        StringBuilder sb = new StringBuilder(m.getName());
                        if (a != null) {
                            for (int i = 0; i < a.length; i++) {
                                sb.append(' ');
                                if (a[i] instanceof float[]) {
                                    float[] f = (float[]) a[i];
                                    sb.append('[');
                                    for (int k = 0; k < f.length && k < 4; k++) sb.append(f[k]).append(k < f.length - 1 && k < 3 ? "," : "");
                                    sb.append(']');
                                } else if (a[i] instanceof java.nio.Buffer) sb.append("buf");
                                else sb.append(a[i]);
                            }
                        }
                        if (m.getName().equals("glLoadMatrixf")) loads.add(((float[]) a[0]).clone());
                        calls.add(sb.toString());
                        Class<?> t = m.getReturnType();
                        return t == int.class ? Integer.valueOf(0) : null;
                    }
                });
        GLES1Renderer r = new GLES1Renderer(new Engine(), null);
        r.onSurfaceChanged(gl, 100, 100);   // gives the renderer its GL object without touching Log
        calls.clear();

        r.setFog(true, 10f, 50f, 0.2f, 0.4f, 0.6f);
        check(calls.contains("glFogf " + GL10.GL_FOG_START + " 10.0") && calls.contains("glFogf " + GL10.GL_FOG_END + " 50.0"),
                "fog ES1: start and end are passed to GL");
        check(calls.contains("glFogfv " + GL10.GL_FOG_COLOR + " [0.2,0.4,0.6,1.0] 0"), "fog ES1: fog color (alpha 1) is passed to GL");
        check(count("glEnable " + GL10.GL_FOG) == 1, "fog ES1: GL_FOG is enabled");

        calls.clear();
        r.setFog(true, 10f, 50f, 0.2f, 0.4f, 0.6f);
        check(count("glEnable " + GL10.GL_FOG) == 0, "fog ES1: GL_FOG is not enabled twice");

        calls.clear();
        float[] quad = new float[Renderer.QUAD_FLOATS_PER_VERTEX * 4];
        r.drawQuads(0, quad, 1);
        int draw = last("glDrawElements"), off = last("glDisable " + GL10.GL_FOG), on = last("glEnable " + GL10.GL_FOG);
        check(off >= 0 && off < draw && on > draw, "fog ES1: sprites are drawn with fog off, fog is on again right after");

        calls.clear();
        r.setFog(false, 10f, 50f, 0f, 0f, 0f);
        check(count("glDisable " + GL10.GL_FOG) == 1 && count("glEnable " + GL10.GL_FOG) == 0, "fog ES1: setFog(false) disables GL_FOG");
        calls.clear();
        r.drawQuads(0, quad, 1);
        check(count("glEnable " + GL10.GL_FOG) == 0, "fog ES1: after setFog(false) sprites do not switch fog back on");

        calls.clear();
        r.setFog(true, 30f, 30f, 1f, 1f, 1f);
        r.setFog(true, 40f, 10f, 1f, 1f, 1f);
        check(count("glEnable " + GL10.GL_FOG) == 0 && count("glFogf") == 0, "fog ES1: end <= start is rejected (fog stays off)");

        // ---- sky
        skarph.core.MeshData dome = skarph.core.Sky.dome(50f, 12, 6, 0.1f, 0.2f, 0.9f, 0.8f, 0.85f, 0.9f, 0.3f, 0.3f, 0.3f);
        int sky = r.createMesh(dome);
        skarph.core.Mat4 proj = new skarph.core.Mat4().setPerspective(50f, 1.6f, 0.5f, 100f);
        skarph.core.Mat4 view = new skarph.core.Mat4().setLookAt(3, 4, 9, 0, 1, 0, 0, 1, 0);
        r.setLighting(true);
        r.setFog(true, 10f, 50f, 0.5f, 0.5f, 0.5f);
        r.setCamera(proj, view);
        calls.clear();
        loads.clear();
        r.drawSky(sky);
        int dis = last("glDisable " + GL10.GL_DEPTH_TEST), drw = last("glDrawElements"), en = last("glEnable " + GL10.GL_DEPTH_TEST);
        check(dis >= 0 && dis < drw && en > drw, "sky ES1: depth test is off while the sky is drawn and on again afterwards");
        int cullOff = last("glDisable " + GL10.GL_CULL_FACE), cullOn = last("glEnable " + GL10.GL_CULL_FACE);
        check(cullOff >= 0 && cullOff < drw && cullOn > drw, "sky ES1: culling is off while the sky is drawn");
        int fogOff = last("glDisable " + GL10.GL_FOG), fogOn = last("glEnable " + GL10.GL_FOG);
        check(fogOff >= 0 && fogOff < drw && fogOn > drw, "sky ES1: fog is off for the sky and on again afterwards");
        check(count("glEnable " + GL10.GL_LIGHTING) == 0 && count("glNormalPointer") == 0, "sky ES1: never lit");
        float[] first = loads.size() > 0 ? loads.get(0) : null, lastL = loads.size() > 1 ? loads.get(loads.size() - 1) : null;
        boolean rotation = first != null && first[12] == 0f && first[13] == 0f && first[14] == 0f && first[15] == 1f;
        for (int i = 0; rotation && i < 12; i++) rotation &= first[i] == view.m[i];
        boolean restored = lastL != null;
        for (int i = 0; restored && i < 16; i++) restored &= lastL[i] == view.m[i];
        check(rotation, "sky ES1: the view matrix is loaded without its translation (the sky follows the camera)");
        check(restored && view.m[12] != 0f, "sky ES1: the full camera matrix is loaded again afterwards");
        calls.clear();
        r.drawSky(-1);
        r.drawSky(60);
        check(calls.isEmpty(), "sky ES1: invalid mesh handles are ignored");

        // ---- billboards (texture handle 0 is not loaded here, like in the drawQuads test above)
        float[] bd = new float[2 * Renderer.BILLBOARD_FLOATS];
        for (int i = 0; i < 2; i++) { bd[i * 13 + 3] = 2f; bd[i * 13 + 4] = 2f; bd[i * 13 + 12] = 1f; }
        r.setLighting(true);
        r.setFog(true, 10f, 50f, 0.5f, 0.5f, 0.5f);
        r.setCamera(proj, view);
        calls.clear();
        r.drawBillboards(0, bd, 2, true);
        int bdraw = last("glDrawElements");
        check(count("glDrawElements") == 1 && calls.get(bdraw).contains(" 12 "), "billboards ES1: one draw call, 2 billboards = 12 indices");
        check(last("glDepthMask false") >= 0 && last("glDepthMask false") < bdraw && last("glDepthMask true") > bdraw, "billboards ES1: depth write is off while drawing and on again afterwards");
        check(last("glBlendFunc " + GL10.GL_SRC_ALPHA + " " + GL10.GL_ONE) >= 0 && last("glBlendFunc " + GL10.GL_SRC_ALPHA + " " + GL10.GL_ONE) < bdraw, "billboards ES1: additive blending = SRC_ALPHA, ONE");
        int cOff = last("glDisable " + GL10.GL_CULL_FACE), cOn = last("glEnable " + GL10.GL_CULL_FACE);
        check(cOff >= 0 && cOff < bdraw && cOn > bdraw && count("glEnable " + GL10.GL_LIGHTING) == 0 && count("glNormalPointer") == 0, "billboards ES1: no culling, never lit");
        int black = last("glFogfv " + GL10.GL_FOG_COLOR + " [0.0,0.0,0.0,1.0] 0"), real = last("glFogfv " + GL10.GL_FOG_COLOR + " [0.5,0.5,0.5,1.0] 0");
        check(black >= 0 && black < bdraw && real > bdraw, "billboards ES1: additive billboards fade to black, the fog color is restored afterwards");
        calls.clear();
        r.drawBillboards(0, bd, 2, false);
        check(last("glBlendFunc " + GL10.GL_SRC_ALPHA + " " + GL10.GL_ONE_MINUS_SRC_ALPHA) >= 0 && count("glFogfv") == 0, "billboards ES1: alpha billboards use alpha blending and keep the fog color");
        calls.clear();
        r.drawBillboards(-1, bd, 2, false);
        r.drawBillboards(0, bd, 0, false);
        check(calls.isEmpty(), "billboards ES1: an invalid texture or a count of 0 draws nothing");

        System.out.println(failed == 0 ? "ALL ES1 FOG TESTS PASSED" : (failed + " ES1 FOG TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
