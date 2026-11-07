// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import java.nio.Buffer;

import android.opengl.GLES20;

/**
 * Gl2 on top of android.opengl.GLES20 (Android 2.3 / API 9 and newer: the offset variants of
 * glVertexAttribPointer and glDrawElements do not exist before). Isolated in its own class:
 * it is only loaded after EngineView has checked the Android version and the device's OpenGL ES version.
 */
final class AndroidGl2 implements Gl2 {
    private final int[] tmp = new int[1];

    public int createShader(int type) {
        return GLES20.glCreateShader(type);
    }

    public void shaderSource(int shader, String source) {
        GLES20.glShaderSource(shader, source);
    }

    public void compileShader(int shader) {
        GLES20.glCompileShader(shader);
    }

    public int getShaderi(int shader, int pname) {
        GLES20.glGetShaderiv(shader, pname, tmp, 0);
        return tmp[0];
    }

    public String getShaderInfoLog(int shader) {
        return GLES20.glGetShaderInfoLog(shader);
    }

    public void deleteShader(int shader) {
        GLES20.glDeleteShader(shader);
    }

    public int createProgram() {
        return GLES20.glCreateProgram();
    }

    public void attachShader(int program, int shader) {
        GLES20.glAttachShader(program, shader);
    }

    public void bindAttribLocation(int program, int index, String name) {
        GLES20.glBindAttribLocation(program, index, name);
    }

    public void linkProgram(int program) {
        GLES20.glLinkProgram(program);
    }

    public int getProgrami(int program, int pname) {
        GLES20.glGetProgramiv(program, pname, tmp, 0);
        return tmp[0];
    }

    public String getProgramInfoLog(int program) {
        return GLES20.glGetProgramInfoLog(program);
    }

    public void useProgram(int program) {
        GLES20.glUseProgram(program);
    }

    public void deleteProgram(int program) {
        GLES20.glDeleteProgram(program);
    }

    public int getUniformLocation(int program, String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    public void uniform1i(int location, int value) {
        GLES20.glUniform1i(location, value);
    }

    public void uniform3f(int location, float x, float y, float z) {
        GLES20.glUniform3f(location, x, y, z);
    }

    public void uniformMatrix3fv(int location, float[] value) {
        GLES20.glUniformMatrix3fv(location, 1, false, value, 0);
    }

    public void uniformMatrix4fv(int location, float[] value) {
        GLES20.glUniformMatrix4fv(location, 1, false, value, 0);
    }

    public int genBuffer() {
        GLES20.glGenBuffers(1, tmp, 0);
        return tmp[0];
    }

    public void bindBuffer(int target, int buffer) {
        GLES20.glBindBuffer(target, buffer);
    }

    public void bufferData(int target, int sizeBytes, Buffer data, int usage) {
        GLES20.glBufferData(target, sizeBytes, data, usage);
    }

    public void deleteBuffer(int buffer) {
        tmp[0] = buffer;
        GLES20.glDeleteBuffers(1, tmp, 0);
    }

    public int genTexture() {
        GLES20.glGenTextures(1, tmp, 0);
        return tmp[0];
    }

    public void bindTexture(int target, int texture) {
        GLES20.glBindTexture(target, texture);
    }

    public void deleteTexture(int texture) {
        tmp[0] = texture;
        GLES20.glDeleteTextures(1, tmp, 0);
    }

    public void activeTexture(int unit) {
        GLES20.glActiveTexture(unit);
    }

    public void texParameteri(int target, int pname, int value) {
        GLES20.glTexParameteri(target, pname, value);
    }

    public void generateMipmap(int target) {
        GLES20.glGenerateMipmap(target);
    }

    public void enableVertexAttribArray(int index) {
        GLES20.glEnableVertexAttribArray(index);
    }

    public void disableVertexAttribArray(int index) {
        GLES20.glDisableVertexAttribArray(index);
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) {
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, offset);
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer pointer) {
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    public void drawElements(int mode, int count, int type, int offset) {
        GLES20.glDrawElements(mode, count, type, offset);
    }

    public void drawElements(int mode, int count, int type, Buffer indices) {
        GLES20.glDrawElements(mode, count, type, indices);
    }

    public void enable(int cap) {
        GLES20.glEnable(cap);
    }

    public void disable(int cap) {
        GLES20.glDisable(cap);
    }

    public void blendFunc(int src, int dst) {
        GLES20.glBlendFunc(src, dst);
    }

    public void depthMask(boolean write) {
        GLES20.glDepthMask(write);
    }

    public void frontFace(int mode) {
        GLES20.glFrontFace(mode);
    }

    public void cullFace(int mode) {
        GLES20.glCullFace(mode);
    }

    public void viewport(int x, int y, int width, int height) {
        GLES20.glViewport(x, y, width, height);
    }

    public void clearColor(float r, float g, float b, float a) {
        GLES20.glClearColor(r, g, b, a);
    }

    public void clear(int mask) {
        GLES20.glClear(mask);
    }

    public int getError() {
        return GLES20.glGetError();
    }

    public String getString(int name) {
        return GLES20.glGetString(name);
    }
}
