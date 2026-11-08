// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
/* Compiles, links and RENDERS every shader variant of the engine on a real GLSL ES compiler (Mesa llvmpipe,
 * headless EGL) and compares the output color with the expected value computed on the CPU.
 * usage: harness <shader dir>   (files written by DumpShaders) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

static int failures = 0;
static const char *ATTRS[5] = {"aPos", "aNormal", "aColor", "aUv0", "aUv1"};
#define W 16
#define H 16

static char *slurp(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *s = malloc(n + 1); fread(s, 1, n, f); s[n] = 0; fclose(f); return s;
}

static GLuint compile(GLenum type, const char *src, const char *name) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, NULL);
    glCompileShader(sh);
    GLint ok; glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[2048]; glGetShaderInfoLog(sh, sizeof log, NULL, log); printf("FAIL compile %s: %s\n", name, log); failures++; return 0; }
    return sh;
}

static GLuint program(const char *dir, const char *base) {
    char p1[512], p2[512];
    snprintf(p1, sizeof p1, "%s/%s.vert", dir, base);
    snprintf(p2, sizeof p2, "%s/%s.frag", dir, base);
    GLuint vs = compile(GL_VERTEX_SHADER, slurp(p1), p1);
    GLuint fs = compile(GL_FRAGMENT_SHADER, slurp(p2), p2);
    if (!vs || !fs) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, vs); glAttachShader(p, fs);
    for (int i = 0; i < 5; i++) glBindAttribLocation(p, i, ATTRS[i]);   /* same as GLES2Renderer */
    glLinkProgram(p);
    GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char log[2048]; glGetProgramInfoLog(p, sizeof log, NULL, log); printf("FAIL link %s: %s\n", base, log); failures++; return 0; }
    return p;
}

static GLuint texture2x2(unsigned char r, unsigned char g, unsigned char b, unsigned char a) {
    unsigned char px[16];
    for (int i = 0; i < 4; i++) { px[i*4] = r; px[i*4+1] = g; px[i*4+2] = b; px[i*4+3] = a; }
    GLuint t; glGenTextures(1, &t); glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
    return t;
}

static const float IDENT4[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
static const float IDENT3[9] = {1,0,0, 0,1,0, 0,0,1};

/* draws one big triangle with the given attributes, reads the center pixel */
static void draw(GLuint prog, int features, int sprite, const float *color4, const float *normal3,
                 const float *lightDir, const float *ambient, const float *lightColor, float out[4]) {
    glUseProgram(prog);
    glUniformMatrix4fv(glGetUniformLocation(prog, "uMvp"), 1, GL_FALSE, IDENT4);
    glUniformMatrix3fv(glGetUniformLocation(prog, "uNormalMat"), 1, GL_FALSE, IDENT3);
    glUniform3f(glGetUniformLocation(prog, "uLightDir"), lightDir[0], lightDir[1], lightDir[2]);
    glUniform3f(glGetUniformLocation(prog, "uAmbient"), ambient[0], ambient[1], ambient[2]);
    glUniform3f(glGetUniformLocation(prog, "uLightColor"), lightColor[0], lightColor[1], lightColor[2]);
    glUniform1i(glGetUniformLocation(prog, "uTex0"), 0);
    glUniform1i(glGetUniformLocation(prog, "uTex1"), 1);

    float pos3[9] = {-1,-1,0, 3,-1,0, -1,3,0};
    float pos2[6] = {-1,-1, 3,-1, -1,3};
    float nrm[9], col[12], uv[6];
    for (int i = 0; i < 3; i++) {
        for (int k = 0; k < 3; k++) nrm[i*3+k] = normal3[k];
        for (int k = 0; k < 4; k++) col[i*4+k] = color4[k];
        uv[i*2] = 0.25f; uv[i*2+1] = 0.25f;
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, sprite ? 2 : 3, GL_FLOAT, GL_FALSE, 0, sprite ? (void*)pos2 : (void*)pos3);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, 0, col);
    if (features & 1) { glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 0, nrm); }
    if (features & 2 || sprite) { glEnableVertexAttribArray(3); glVertexAttribPointer(3, 2, GL_FLOAT, GL_FALSE, 0, uv); }
    if (features & 4) { glEnableVertexAttribArray(4); glVertexAttribPointer(4, 2, GL_FLOAT, GL_FALSE, 0, uv); }
    glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    unsigned char px[4];
    glReadPixels(W/2, H/2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    for (int i = 0; i < 4; i++) out[i] = px[i] / 255.0f;
    for (int i = 0; i < 5; i++) glDisableVertexAttribArray(i);
}

static void expect(const char *name, const float got[4], const float want[4]) {
    int ok = 1;
    for (int i = 0; i < 4; i++) if (fabsf(got[i] - want[i]) > 2.5f / 255.0f) ok = 0;
    printf("%s %s: got (%.3f %.3f %.3f %.3f) want (%.3f %.3f %.3f %.3f)\n", ok ? "PASS" : "FAIL", name,
           got[0], got[1], got[2], got[3], want[0], want[1], want[2], want[3]);
    if (!ok) failures++;
}

static float clamp1(float v) { return v > 1.0f ? 1.0f : v; }

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : ".";
    PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay dpy = getDisplay ? getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj, min;
    if (!eglInitialize(dpy, &maj, &min)) { printf("FAIL eglInitialize\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE};
    EGLConfig cfg; EGLint n;
    if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n < 1) { printf("FAIL no EGL config\n"); return 1; }
    EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) { printf("FAIL context\n"); return 1; }
    printf("GL_RENDERER=%s GL_VERSION=%s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    GLuint fbo, color; glGenFramebuffers(1, &fbo); glGenTextures(1, &color);
    glBindTexture(GL_TEXTURE_2D, color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { printf("FAIL framebuffer\n"); return 1; }
    glViewport(0, 0, W, H);

    /* 1. every variant must compile and link */
    GLuint prog[9];
    char name[32];
    for (int f = 0; f < 8; f++) { snprintf(name, sizeof name, "mesh_%d", f); prog[f] = program(dir, name); printf("%s mesh variant %d compiles and links\n", prog[f] ? "PASS" : "FAIL", f); }
    for (int f = 8; f < 16; f++) { GLuint fp; snprintf(name, sizeof name, "mesh_%d", f); fp = program(dir, name); printf("%s fog mesh variant %d compiles and links\n", fp ? "PASS" : "FAIL", f); }
    prog[8] = program(dir, "sprite"); printf("%s sprite program compiles and links\n", prog[8] ? "PASS" : "FAIL");
    if (failures) { printf("%d FAILURE(S)\n", failures); return 1; }

    /* 2. render and compare */
    GLuint tex0 = texture2x2(200, 100, 50, 255), tex1 = texture2x2(128, 128, 128, 255), spr = texture2x2(255, 128, 0, 255);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex0);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tex1);
    glActiveTexture(GL_TEXTURE0);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);

    const float c[4] = {0.5f, 0.4f, 0.3f, 1.0f};
    const float nz[3] = {0, 0, 1}, nz2[3] = {0, 0, 2};
    const float toward[3] = {0, 0, 1}, away[3] = {0, 0, -1}, side[3] = {1, 0, 0};
    const float amb[3] = {0.2f, 0.2f, 0.2f}, lc[3] = {0.5f, 0.4f, 0.3f}, amb1[3] = {1.0f, 1.0f, 1.0f};
    float got[4], want[4];
    float t0[3] = {200/255.0f, 100/255.0f, 50/255.0f}, t1 = 128/255.0f;

    draw(prog[0], 0, 0, c, nz, toward, amb, lc, got);
    want[0] = c[0]; want[1] = c[1]; want[2] = c[2]; want[3] = 1; expect("unlit: vertex color", got, want);

    draw(prog[1], 1, 0, c, nz, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = clamp1(c[i] * (amb[i] + lc[i] * 1.0f));
    expect("lit, light facing the surface: color * (ambient + light)", got, want);

    draw(prog[1], 1, 0, c, nz, away, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = c[i] * amb[i];
    expect("lit, light behind the surface: ambient only", got, want);

    draw(prog[1], 1, 0, c, nz, side, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = c[i] * amb[i];
    expect("lit, light at 90 degrees: ambient only", got, want);

    draw(prog[1], 1, 0, c, nz2, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = clamp1(c[i] * (amb[i] + lc[i]));
    expect("lit, long normal is normalized in the shader", got, want);

    draw(prog[2], 2, 0, c, nz, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = c[i] * t0[i];
    expect("diffuse texture: vertex color * texture", got, want);

    draw(prog[6], 6, 0, c, nz, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = c[i] * t0[i] * t1;
    expect("diffuse texture + lightmap: color * tex0 * tex1", got, want);

    draw(prog[4], 4, 0, c, nz, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = c[i] * t1;
    expect("lightmap only: color * tex1 (unit 1)", got, want);

    const float bright[4] = {0.8f, 0.8f, 0.8f, 1.0f};
    draw(prog[7], 7, 0, bright, nz, toward, amb1, lc, got);
    for (int i = 0; i < 3; i++) want[i] = 1.0f * t0[i] * t1;    /* lit clamps at 1 BEFORE the textures */
    expect("all features: lighting is clamped to 1 before texturing", got, want);

    draw(prog[3], 3, 0, c, nz, toward, amb, lc, got);
    for (int i = 0; i < 3; i++) want[i] = clamp1(c[i] * (amb[i] + lc[i])) * t0[i];
    expect("lit + diffuse texture", got, want);

    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, spr);
    const float tint[4] = {1.0f, 1.0f, 1.0f, 0.5f};
    draw(prog[8], 0, 1, tint, nz, toward, amb, lc, got);
    want[0] = 1.0f; want[1] = 128/255.0f; want[2] = 0; want[3] = 0.5f;
    expect("sprite: vertex color (incl. alpha) * texture", got, want);

    printf("%s\n", failures ? "SHADER TESTS FAILED" : "ALL SHADER TESTS PASSED");
    return failures ? 1 : 0;
}
