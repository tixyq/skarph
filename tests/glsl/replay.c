// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
/* Plays a GL trace recorded by TraceDemo on a real OpenGL ES 2.0 implementation (Mesa llvmpipe, headless EGL).
 * Reports compile/link errors and GL errors, writes chosen frames as PPM.
 * usage: replay <trace> <out prefix> [frame numbers to save...]   (frame numbers start at 1) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#define W 800
#define H 480
static unsigned char *buf; static size_t blen, pos;
static int ri(void) { int v; memcpy(&v, buf + pos, 4); pos += 4; return v; }
static float rf(void) { float v; memcpy(&v, buf + pos, 4); pos += 4; return v; }
static const unsigned char *rb(int *n) { *n = ri(); const unsigned char *p = buf + pos; pos += *n; return p; }
static char *rs(void) { int n; const unsigned char *p = rb(&n); char *s = malloc(n + 1); memcpy(s, p, n); s[n] = 0; return s; }

static int ptr_size[8], ptr_type[8], ptr_norm[8], ptr_stride[8];
static GLuint shaderMap[4096], programMap[4096], bufMap[4096], texMap[4096];
static GLint uloc[256][256];
static int errors = 0, draws = 0, frames = 0;

static void check(const char *where, long op) {
    GLenum e;
    while ((e = glGetError()) != GL_NO_ERROR) { if (errors < 10) printf("GL ERROR 0x%x after %s (op #%ld, frame %d)\n", e, where, op, frames + 1); errors++; }
}

static void save(const char *prefix, int frame) {
    unsigned char *px = malloc(W * H * 4);
    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
    char name[512]; snprintf(name, sizeof name, "%s_%d.ppm", prefix, frame);
    FILE *f = fopen(name, "wb"); fprintf(f, "P6\n%d %d\n255\n", W, H);
    for (int y = H - 1; y >= 0; y--) for (int x = 0; x < W; x++) fwrite(px + (y * W + x) * 4, 1, 3, f);
    fclose(f); free(px); printf("saved %s\n", name);
}

int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); if (!f) { printf("cannot open trace\n"); return 2; }
    fseek(f, 0, SEEK_END); blen = ftell(f); fseek(f, 0, SEEK_SET); buf = malloc(blen); fread(buf, 1, blen, f); fclose(f);

    PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay dpy = getDisplay ? getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj, min; eglInitialize(dpy, &maj, &min); eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE};
    EGLConfig cfg; EGLint n; eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) { printf("no context\n"); return 2; }
    printf("GL_RENDERER=%s\n", glGetString(GL_RENDERER));
    GLuint fbo, color, depth; glGenFramebuffers(1, &fbo); glGenTextures(1, &color); glGenRenderbuffers(1, &depth);
    glBindTexture(GL_TEXTURE_2D, color); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glBindRenderbuffer(GL_RENDERBUFFER, depth); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, W, H);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { printf("framebuffer incomplete\n"); return 2; }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, 0);

    int curProg = 0; long op = 0; int bound_array = 0;
    while (pos < blen) {
        int code = ri(); op++;
        switch (code) {
        case 1: { int type = ri(), id = ri(); shaderMap[id] = glCreateShader(type); break; }
        case 2: { int id = ri(); char *s = rs(); glShaderSource(shaderMap[id], 1, (const char **)&s, NULL); free(s); break; }
        case 3: { int id = ri(); glCompileShader(shaderMap[id]); GLint ok; glGetShaderiv(shaderMap[id], GL_COMPILE_STATUS, &ok);
                  if (!ok) { char log[2048]; glGetShaderInfoLog(shaderMap[id], sizeof log, NULL, log); printf("COMPILE ERROR: %s\n", log); errors++; } break; }
        case 4: { int id = ri(); glDeleteShader(shaderMap[id]); break; }
        case 5: { int id = ri(); programMap[id] = glCreateProgram(); break; }
        case 6: { int p = ri(), s = ri(); glAttachShader(programMap[p], shaderMap[s]); break; }
        case 7: { int p = ri(), idx = ri(); char *s = rs(); glBindAttribLocation(programMap[p], idx, s); free(s); break; }
        case 8: { int p = ri(); glLinkProgram(programMap[p]); GLint ok; glGetProgramiv(programMap[p], GL_LINK_STATUS, &ok);
                  if (!ok) { char log[2048]; glGetProgramInfoLog(programMap[p], sizeof log, NULL, log); printf("LINK ERROR: %s\n", log); errors++; } break; }
        case 9: { int p = ri(); curProg = p; glUseProgram(programMap[p]); break; }
        case 10: { int p = ri(); glDeleteProgram(programMap[p]); break; }
        case 11: { int p = ri(); char *name = rs(); int fake = ri(); uloc[p][fake] = glGetUniformLocation(programMap[p], name); free(name); break; }
        case 12: { int p = ri(), l = ri(), v = ri(); if (uloc[p][l] >= 0) glUniform1i(uloc[p][l], v); break; }
        case 13: { int p = ri(), l = ri(); float x = rf(), y = rf(), z = rf(); if (uloc[p][l] >= 0) glUniform3f(uloc[p][l], x, y, z); break; }
        case 14: { int p = ri(), l = ri(); float m[9]; for (int k = 0; k < 9; k++) m[k] = rf(); if (uloc[p][l] >= 0) glUniformMatrix3fv(uloc[p][l], 1, GL_FALSE, m); break; }
        case 15: { int p = ri(), l = ri(); float m[16]; for (int k = 0; k < 16; k++) m[k] = rf(); if (uloc[p][l] >= 0) glUniformMatrix4fv(uloc[p][l], 1, GL_FALSE, m); break; }
        case 16: { int id = ri(); glGenBuffers(1, &bufMap[id]); break; }
        case 17: { int target = ri(), b = ri(); glBindBuffer(target, b ? bufMap[b] : 0); break; }
        case 18: { int target = ri(), usage = ri(), len; const unsigned char *d = rb(&len); glBufferData(target, len, d, usage); break; }
        case 19: { int id = ri(); glDeleteBuffers(1, &bufMap[id]); break; }
        case 20: { int id = ri(); glGenTextures(1, &texMap[id]); break; }
        case 21: { int target = ri(), t = ri(); glBindTexture(target, t ? texMap[t] : 0); break; }
        case 22: { int id = ri(); glDeleteTextures(1, &texMap[id]); break; }
        case 23: { int u = ri(); glActiveTexture(u); break; }
        case 24: { int target = ri(), pn = ri(), v = ri(); glTexParameteri(target, pn, v); break; }
        case 25: { int target = ri(); glGenerateMipmap(target); break; }
        case 26: { int w = ri(), h = ri(), len; const unsigned char *d = rb(&len); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, d); break; }
        case 27: { int i = ri(); glEnableVertexAttribArray(i); break; }
        case 28: { int i = ri(); glDisableVertexAttribArray(i); break; }
        case 29: { int i = ri(), size = ri(), type = ri(), norm = ri(), stride = ri(), off = ri(); glVertexAttribPointer(i, size, type, norm, stride, (void *)(size_t)off); break; }
        case 30: { int i = ri(); ptr_size[i] = ri(); ptr_type[i] = ri(); ptr_norm[i] = ri(); ptr_stride[i] = ri(); break; } /* client pointer: applied at the draw, together with its data */
        case 31: { int mode = ri(), count = ri(), type = ri(), off = ri(); glDrawElements(mode, count, type, (void *)(size_t)off); draws++; check("drawElements (VBO)", op); break; }
        case 32: {
            int mode = ri(), count = ri(), type = ri(), il; const unsigned char *idx = rb(&il); int na = ri();
            unsigned char *indices = malloc(il); memcpy(indices, idx, il);
            for (int k = 0; k < na; k++) {
                int a = ri(), len; const unsigned char *d = rb(&len); unsigned char *copy = malloc(len); memcpy(copy, d, len);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                glVertexAttribPointer(a, ptr_size[a], ptr_type[a], ptr_norm[a], ptr_stride[a], copy);
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            glDrawElements(mode, count, type, indices); draws++; check("drawElements (client arrays)", op);
            break; }
        case 33: { int c = ri(); glEnable(c); break; }
        case 34: { int c = ri(); glDisable(c); break; }
        case 35: { int s = ri(), d = ri(); glBlendFunc(s, d); break; }
        case 36: { int m = ri(); glFrontFace(m); break; }
        case 37: { int m = ri(); glCullFace(m); break; }
        case 38: { int x = ri(), y = ri(), w = ri(), h = ri(); glViewport(x, y, w, h); break; }
        case 39: { float r = rf(), g = rf(), b = rf(), a = rf(); glClearColor(r, g, b, a); break; }
        case 40: { int m = ri(); glClear(m); break; }
        case 41: { check("frame end", op); frames++;
                   for (int a = 3; a < argc; a++) if (atoi(argv[a]) == frames) save(argv[2], frames); break; }
        case 42: { int m = ri(); glDepthMask(m ? GL_TRUE : GL_FALSE); break; }
        default: printf("unknown opcode %d at op #%ld\n", code, op); return 3;
        }
    }
    printf("replayed %ld GL calls, %d frames, %d draw calls, %d GL error(s)\n", op, frames, draws, errors);
    printf("%s\n", errors ? "REPLAY FAILED" : "REPLAY OK");
    return errors ? 1 : 0;
}
