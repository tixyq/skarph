// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.util.regex.Pattern;

/**
 * Turns the GLSL ES 1.00 sources of GLES2Shaders into GLSL 1.10 for desktop OpenGL 2.1 (also the legacy context of
 * macOS). The two languages are the same here except for the precision qualifiers, which desktop 1.10 does not know.
 */
public final class ShaderTranslator {
    private static final Pattern PRECISION_STATEMENT = Pattern.compile("(?m)^[ \\t]*precision[ \\t]+\\w+[ \\t]+\\w+[ \\t]*;[ \\t]*$");

    private ShaderTranslator() {
    }

    public static String toDesktop(String es) {
        String body = PRECISION_STATEMENT.matcher(es).replaceAll("");
        return "#version 110\n#define highp\n#define mediump\n#define lowp\n" + body;
    }
}
