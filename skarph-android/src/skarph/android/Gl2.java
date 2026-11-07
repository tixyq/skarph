// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import java.nio.Buffer;

/**
 * The small part of OpenGL ES 2.0 that GLES2Renderer needs. The Android implementation
 * (AndroidGl2) forwards to android.opengl.GLES20; tests use a fake. Constants are the values from the
 * OpenGL ES 2.0 specification, so the renderer logic contains no Android classes.
 */
public interface Gl2 {
    int GL_FALSE = 0;
    int GL_TRUE = 1;
    int GL_NO_ERROR = 0;
    int GL_DEPTH_BUFFER_BIT = 0x00000100;
    int GL_COLOR_BUFFER_BIT = 0x00004000;
    int GL_TRIANGLES = 0x0004;
    int GL_ONE = 1;
    int GL_SRC_ALPHA = 0x0302;
    int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    int GL_CULL_FACE = 0x0B44;
    int GL_DEPTH_TEST = 0x0B71;
    int GL_DITHER = 0x0BD0;
    int GL_BLEND = 0x0BE2;
    int GL_BACK = 0x0405;
    int GL_CCW = 0x0901;
    int GL_TEXTURE_2D = 0x0DE1;
    int GL_UNSIGNED_BYTE = 0x1401;
    int GL_UNSIGNED_SHORT = 0x1403;
    int GL_FLOAT = 0x1406;
    int GL_VENDOR = 0x1F00;
    int GL_RENDERER = 0x1F01;
    int GL_VERSION = 0x1F02;
    int GL_NEAREST = 0x2600;
    int GL_LINEAR = 0x2601;
    int GL_LINEAR_MIPMAP_NEAREST = 0x2701;
    int GL_TEXTURE_MAG_FILTER = 0x2800;
    int GL_TEXTURE_MIN_FILTER = 0x2801;
    int GL_TEXTURE_WRAP_S = 0x2802;
    int GL_TEXTURE_WRAP_T = 0x2803;
    int GL_REPEAT = 0x2901;
    int GL_CLAMP_TO_EDGE = 0x812F;
    int GL_TEXTURE0 = 0x84C0;
    int GL_TEXTURE1 = 0x84C1;
    int GL_ARRAY_BUFFER = 0x8892;
    int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    int GL_STATIC_DRAW = 0x88E4;
    int GL_FRAGMENT_SHADER = 0x8B30;
    int GL_VERTEX_SHADER = 0x8B31;
    int GL_COMPILE_STATUS = 0x8B81;
    int GL_LINK_STATUS = 0x8B82;

    // shaders and programs
    int createShader(int type);
    void shaderSource(int shader, String source);
    void compileShader(int shader);
    int getShaderi(int shader, int pname);
    String getShaderInfoLog(int shader);
    void deleteShader(int shader);
    int createProgram();
    void attachShader(int program, int shader);
    void bindAttribLocation(int program, int index, String name);
    void linkProgram(int program);
    int getProgrami(int program, int pname);
    String getProgramInfoLog(int program);
    void useProgram(int program);
    void deleteProgram(int program);
    int getUniformLocation(int program, String name);

    // uniforms (matrices are column-major, not transposed)
    void uniform1i(int location, int value);
    void uniform3f(int location, float x, float y, float z);
    void uniformMatrix3fv(int location, float[] value);
    void uniformMatrix4fv(int location, float[] value);

    // buffers
    int genBuffer();
    void bindBuffer(int target, int buffer);
    void bufferData(int target, int sizeBytes, Buffer data, int usage);
    void deleteBuffer(int buffer);

    // textures
    int genTexture();
    void bindTexture(int target, int texture);
    void deleteTexture(int texture);
    void activeTexture(int unit);
    void texParameteri(int target, int pname, int value);
    void generateMipmap(int target);

    // vertex attributes and drawing
    void enableVertexAttribArray(int index);
    void disableVertexAttribArray(int index);
    /** Attribute data from the bound GL_ARRAY_BUFFER (offset in bytes). */
    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset);
    /** Attribute data from client memory (the buffer's current position is the start). */
    void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer pointer);
    /** Indices from the bound GL_ELEMENT_ARRAY_BUFFER. */
    void drawElements(int mode, int count, int type, int offset);
    /** Indices from client memory. */
    void drawElements(int mode, int count, int type, Buffer indices);

    // state
    void enable(int cap);
    void disable(int cap);
    void blendFunc(int src, int dst);
    /** false = the depth buffer is not written (the depth test itself stays as it is). */
    void depthMask(boolean write);
    void frontFace(int mode);
    void cullFace(int mode);
    void viewport(int x, int y, int width, int height);
    void clearColor(float r, float g, float b, float a);
    void clear(int mask);
    int getError();
    String getString(int name);
}
