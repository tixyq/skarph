package javax.microedition.khronos.opengles;
import java.nio.Buffer;
/** Signature stub (compile check only). */
public interface GL11 extends GL10 {
    int GL_ARRAY_BUFFER = 0x8892;
    int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    int GL_GENERATE_MIPMAP = 0x8191;
    int GL_STATIC_DRAW = 0x88E4;
    void glBindBuffer(int target, int buffer);
    void glBufferData(int target, int size, Buffer data, int usage);
    void glGenBuffers(int n, int[] buffers, int offset);
    void glDeleteBuffers(int n, int[] buffers, int offset);
    void glVertexPointer(int size, int type, int stride, int offset);
    void glNormalPointer(int type, int stride, int offset);
    void glColorPointer(int size, int type, int stride, int offset);
    void glTexCoordPointer(int size, int type, int stride, int offset);
    void glDrawElements(int mode, int count, int type, int offset);
}
