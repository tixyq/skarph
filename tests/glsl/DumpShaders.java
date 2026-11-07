// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.android.GLES2Shaders;
import java.io.FileWriter;

/** Writes every shader variant of GLES2Shaders to files, so that real GLSL compilers can check them. */
public class DumpShaders {
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        for (int f = 0; f < GLES2Shaders.VARIANTS; f++) {
            write(dir + "/mesh_" + f + ".vert", GLES2Shaders.meshVertex(f));
            write(dir + "/mesh_" + f + ".frag", GLES2Shaders.meshFragment(f));
        }
        write(dir + "/sprite.vert", GLES2Shaders.spriteVertex());
        write(dir + "/sprite.frag", GLES2Shaders.spriteFragment());
        System.out.println("wrote " + (GLES2Shaders.VARIANTS * 2 + 2) + " shader files to " + dir);
    }

    static void write(String path, String text) throws Exception {
        FileWriter w = new FileWriter(path);
        w.write(text);
        w.close();
    }
}
