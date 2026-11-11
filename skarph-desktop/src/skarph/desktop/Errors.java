// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.awt.GraphicsEnvironment;

/** Tells the person that the program cannot start: a dialog if there is a screen, always the standard error stream. */
public final class Errors {
    private Errors() {
    }

    public static void show(String title, String message) {
        System.err.println("skarph: " + title + ": " + message);
        if (System.getProperty("skarph.child") != null && isMac()) {
            macDialog(title, message); // a Swing dialog cannot be used with -XstartOnFirstThread
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, title, javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // the message is on the standard error stream
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static void macDialog(String title, String message) {
        try {
            String m = message.replace("\\", "\\\\").replace("\"", "'");
            String t = title.replace("\\", "\\\\").replace("\"", "'");
            new ProcessBuilder("osascript", "-e", "display dialog \"" + m + "\" with title \"" + t
                    + "\" buttons {\"OK\"} with icon stop").start().waitFor();
        } catch (Exception ignored) {
            // the message is on the standard error stream
        }
    }
}
