// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

/**
 * Optional second interface of a {@link Game}: before createScreen() the desktop layer hands over the keyboard and
 * mouse-look state of the window. A game that does not need it (the touch-only demos) just does not implement it.
 */
public interface InputAware {
    void setInput(DesktopInput input);
}
