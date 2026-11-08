// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop.host;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import skarph.android.Gl2;
import skarph.desktop.ShaderTranslator;

/**
 * Gl2 on desktop OpenGL 2.1 or newer (a compatibility or legacy context: the shaders use the attribute / varying
 * syntax and some meshes read vertices from client memory). Every call is forwarded as it is, except that the shader
 * sources are translated from GLSL ES 1.00 and glGenerateMipmap has a fallback for old drivers.
 */
final class LwjglGl2 implements Gl2 {
    private static final int GL_GENERATE_MIPMAP = 0x8191; // OpenGL 1.4

    private final boolean mipmapCore;
    private final boolean mipmapExt;

    LwjglGl2() {
        GLCapabilities caps = GL.getCapabilities();
        mipmapCore = caps.OpenGL30;
        mipmapExt = !mipmapCore && caps.GL_EXT_framebuffer_object;
    }

    public int createShader(int type) {
        return GL20.glCreateShader(type);
    }

    public void shaderSource(int shader, String source) {
        GL20.glShaderSource(shader, ShaderTranslator.toDesktop(source));
    }

    public void compileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    public int getShaderi(int shader, int pname) {
        return GL20.glGetShaderi(shader, pname);
    }

    public String getShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    public void deleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    public int createProgram() {
        return GL20.glCreateProgram();
    }

    public void attachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    public void bindAttribLocation(int program, int index, String name) {
        GL20.glBindAttribLocation(program, index, name);
    }

    public void linkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    public int getProgrami(int program, int pname) {
        return GL20.glGetProgrami(program, pname);
    }

    public String getProgramInfoLog(int program) {
        return GL20.glGetProgramInfoLog(program);
    }

    public void useProgram(int program) {
        GL20.glUseProgram(program);
    }

    public void deleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    public int getUniformLocation(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    public void uniform1i(int location, int value) {
        GL20.glUniform1i(location, value);
    }

    public void uniform3f(int location, float x, float y, float z) {
        GL20.glUniform3f(location, x, y, z);
    }

    public void uniformMatrix3fv(int location, float[] value) {
        GL20.glUniformMatrix3fv(location, false, value);
    }

    public void uniformMatrix4fv(int location, float[] value) {
        GL20.glUniformMatrix4fv(location, false, value);
    }

    public int genBuffer() {
        return GL15.glGenBuffers();
    }

    public void bindBuffer(int target, int buffer) {
        GL15.glBindBuffer(target, buffer);
    }

    public void bufferData(int target, int sizeBytes, Buffer data, int usage) {
        if (data instanceof ByteBuffer) {
            ByteBuffer b = ((ByteBuffer) data).duplicate();
            ((Buffer) b).limit(b.position() + sizeBytes);
            GL15.glBufferData(target, b, usage);
        } else if (data instanceof ShortBuffer) {
            ShortBuffer b = ((ShortBuffer) data).duplicate();
            ((Buffer) b).limit(b.position() + sizeBytes / 2);
            GL15.glBufferData(target, b, usage);
        } else if (data instanceof FloatBuffer) {
            FloatBuffer b = ((FloatBuffer) data).duplicate();
            ((Buffer) b).limit(b.position() + sizeBytes / 4);
            GL15.glBufferData(target, b, usage);
        } else {
            throw new IllegalArgumentException("unsupported buffer " + data);
        }
    }

    public void deleteBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }

    public int genTexture() {
        return GL11.glGenTextures();
    }

    public void bindTexture(int target, int texture) {
        GL11.glBindTexture(target, texture);
    }

    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    public void activeTexture(int unit) {
        GL13.glActiveTexture(unit);
    }

    public void texParameteri(int target, int pname, int value) {
        if (!mipmapCore && !mipmapExt && pname == GL_TEXTURE_MIN_FILTER && value == GL_LINEAR_MIPMAP_NEAREST) {
            GL11.glTexParameteri(target, GL_GENERATE_MIPMAP, GL_TRUE); // no glGenerateMipmap: the driver builds them on upload
        }
        GL11.glTexParameteri(target, pname, value);
    }

    public void generateMipmap(int target) {
        if (mipmapCore) {
            GL30.glGenerateMipmap(target);
        } else if (mipmapExt) {
            EXTFramebufferObject.glGenerateMipmapEXT(target);
        }
    }

    public void enableVertexAttribArray(int index) {
        GL20.glEnableVertexAttribArray(index);
    }

    public void disableVertexAttribArray(int index) {
        GL20.glDisableVertexAttribArray(index);
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, (long) offset);
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer pointer) {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, address(pointer));
    }

    public void drawElements(int mode, int count, int type, int offset) {
        GL11.glDrawElements(mode, count, type, (long) offset);
    }

    public void drawElements(int mode, int count, int type, Buffer indices) {
        GL11.glDrawElements(mode, count, type, address(indices));
    }

    public void enable(int cap) {
        GL11.glEnable(cap);
    }

    public void disable(int cap) {
        GL11.glDisable(cap);
    }

    public void blendFunc(int src, int dst) {
        GL11.glBlendFunc(src, dst);
    }

    public void depthMask(boolean write) {
        GL11.glDepthMask(write);
    }

    public void frontFace(int mode) {
        GL11.glFrontFace(mode);
    }

    public void cullFace(int mode) {
        GL11.glCullFace(mode);
    }

    public void viewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    public void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    public void clear(int mask) {
        GL11.glClear(mask);
    }

    public int getError() {
        return GL11.glGetError();
    }

    public String getString(int name) {
        return GL11.glGetString(name);
    }

    /** The memory address of a direct buffer's current position (client side vertex arrays and indices). */
    private static long address(Buffer b) {
        if (!b.isDirect()) {
            throw new IllegalArgumentException("a direct buffer is needed");
        }
        if (b instanceof ByteBuffer) {
            return MemoryUtil.memAddress((ByteBuffer) b);
        }
        if (b instanceof ShortBuffer) {
            return MemoryUtil.memAddress((ShortBuffer) b);
        }
        if (b instanceof FloatBuffer) {
            return MemoryUtil.memAddress((FloatBuffer) b);
        }
        if (b instanceof IntBuffer) {
            return MemoryUtil.memAddress((IntBuffer) b);
        }
        throw new IllegalArgumentException("unsupported buffer " + b);
    }
}
