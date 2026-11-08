// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

/** The libraries could not be prepared. The message is written for the person who runs the program. */
public final class SetupException extends Exception {
    private static final long serialVersionUID = 1L;

    public SetupException(String message) {
        super(message);
    }

    public SetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
