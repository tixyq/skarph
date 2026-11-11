// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

/**
 * GLSL ES 1.00 shader sources. One vertex/fragment pair is built per feature combination
 * (LIT, TEX0, LM) with #define lines, so no shader ever branches at run time:
 * <pre>
 *   LIT  - one directional light + ambient, per pixel (needs normals)
 *   TEX0 - diffuse texture (uv0)
 *   LM   - baked lightmap texture (uv1), multiplied with the diffuse color
 *   FOG  - linear distance fog, applied last (uFog = scale, offset; uFogColor)
 * </pre>
 * Result: vertex color, lit (clamped to 1) when LIT, times the diffuse texture, times the lightmap,
 * mixed with the fog color when FOG.
 * Fog: the vertex shader computes the unclamped factor (end - w) / (end - start) from the clip space w, which
 * is the view depth for a perspective projection. The factor is linear in w, so the interpolated value is exact;
 * it is clamped per pixel in the fragment shader.
 * Attribute locations are fixed (see ATTR_*), so all programs share the same vertex setup.
 * Fragment precision is highp where the GPU supports it (texture coordinates of tiled textures reach
 * values like 20, which a 16 bit float cannot address exactly), mediump otherwise.
 */
public final class GLES2Shaders {
    public static final int LIT = 1;
    public static final int TEX0 = 2;
    public static final int LM = 4;
    public static final int FOG = 8;
    /** Number of mesh program variants (all combinations of the four features). */
    public static final int VARIANTS = 16;

    public static final int ATTR_POS = 0;
    public static final int ATTR_NORMAL = 1;
    public static final int ATTR_COLOR = 2;
    public static final int ATTR_UV0 = 3;
    public static final int ATTR_UV1 = 4;
    public static final int ATTRIBUTES = 5;

    private static final String MESH_VERTEX =
            "uniform mat4 uMvp;\n"
            + "attribute vec3 aPos;\n"
            + "attribute vec4 aColor;\n"
            + "varying vec4 vColor;\n"
            + "#ifdef LIT\n"
            + "uniform mat3 uNormalMat;\n"
            + "attribute vec3 aNormal;\n"
            + "varying vec3 vNormal;\n"
            + "#endif\n"
            + "#ifdef TEX0\n"
            + "attribute vec2 aUv0;\n"
            + "varying vec2 vUv0;\n"
            + "#endif\n"
            + "#ifdef LM\n"
            + "attribute vec2 aUv1;\n"
            + "varying vec2 vUv1;\n"
            + "#endif\n"
            + "#ifdef FOG\n"
            + "uniform vec3 uFog;\n"   // x = scale, y = offset, z unused
            + "varying float vFog;\n"
            + "#endif\n"
            + "void main() {\n"
            + "    gl_Position = uMvp * vec4(aPos, 1.0);\n"
            + "    vColor = aColor;\n"
            + "#ifdef LIT\n"
            + "    vNormal = uNormalMat * aNormal;\n"
            + "#endif\n"
            + "#ifdef TEX0\n"
            + "    vUv0 = aUv0;\n"
            + "#endif\n"
            + "#ifdef LM\n"
            + "    vUv1 = aUv1;\n"
            + "#endif\n"
            + "#ifdef FOG\n"
            + "    vFog = gl_Position.w * uFog.x + uFog.y;\n"
            + "#endif\n"
            + "}\n";

    private static final String MESH_FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec4 vColor;\n"
            + "#ifdef LIT\n"
            + "uniform vec3 uLightDir;\n"
            + "uniform vec3 uAmbient;\n"
            + "uniform vec3 uLightColor;\n"
            + "varying vec3 vNormal;\n"
            + "#endif\n"
            + "#ifdef TEX0\n"
            + "uniform sampler2D uTex0;\n"
            + "varying vec2 vUv0;\n"
            + "#endif\n"
            + "#ifdef LM\n"
            + "uniform sampler2D uTex1;\n"
            + "varying vec2 vUv1;\n"
            + "#endif\n"
            + "#ifdef FOG\n"
            + "uniform vec3 uFogColor;\n"
            + "varying float vFog;\n"
            + "#endif\n"
            + "void main() {\n"
            + "    vec4 c = vColor;\n"
            + "#ifdef LIT\n"
            + "    float d = max(dot(normalize(vNormal), uLightDir), 0.0);\n"
            + "    c.rgb = min(c.rgb * (uAmbient + uLightColor * d), vec3(1.0));\n"
            + "#endif\n"
            + "#ifdef TEX0\n"
            + "    c *= texture2D(uTex0, vUv0);\n"
            + "#endif\n"
            + "#ifdef LM\n"
            + "    c.rgb *= texture2D(uTex1, vUv1).rgb;\n"
            + "#endif\n"
            + "#ifdef FOG\n"
            + "    c.rgb = mix(uFogColor, c.rgb, clamp(vFog, 0.0, 1.0));\n"
            + "#endif\n"
            + "    gl_FragColor = c;\n"
            + "}\n";

    private static final String SPRITE_VERTEX =
            "uniform mat4 uMvp;\n"
            + "attribute vec2 aPos;\n"
            + "attribute vec2 aUv0;\n"
            + "attribute vec4 aColor;\n"
            + "varying vec2 vUv0;\n"
            + "varying vec4 vColor;\n"
            + "void main() {\n"
            + "    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n"
            + "    vUv0 = aUv0;\n"
            + "    vColor = aColor;\n"
            + "}\n";

    private static final String SPRITE_FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform sampler2D uTex0;\n"
            + "varying vec2 vUv0;\n"
            + "varying vec4 vColor;\n"
            + "void main() {\n"
            + "    gl_FragColor = vColor * texture2D(uTex0, vUv0);\n"
            + "}\n";

    private GLES2Shaders() {
    }

    private static String defines(int features) {
        StringBuilder sb = new StringBuilder();
        if ((features & LIT) != 0) {
            sb.append("#define LIT\n");
        }
        if ((features & TEX0) != 0) {
            sb.append("#define TEX0\n");
        }
        if ((features & LM) != 0) {
            sb.append("#define LM\n");
        }
        if ((features & FOG) != 0) {
            sb.append("#define FOG\n");
        }
        return sb.toString();
    }

    /** Vertex shader source of a mesh program variant (features = OR of LIT, TEX0, LM, FOG). */
    public static String meshVertex(int features) {
        return defines(features) + MESH_VERTEX;
    }

    public static String meshFragment(int features) {
        return defines(features) + MESH_FRAGMENT;
    }

    public static String spriteVertex() {
        return SPRITE_VERTEX;
    }

    public static String spriteFragment() {
        return SPRITE_FRAGMENT;
    }

    /** Attribute names in the order of the ATTR_* locations. */
    public static final String[] ATTRIBUTE_NAMES = {"aPos", "aNormal", "aColor", "aUv0", "aUv1"};
}
