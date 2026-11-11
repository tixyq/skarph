// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Reusable input event holder (filled by InputQueue.poll). */
public final class InputEvent {
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int MOVE = 2;

    public int type;
    public int pointer;
    public float x;
    public float y;
}
