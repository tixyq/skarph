// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Holder for one event of a {@link NetServer}, filled by {@link NetServer#pollEvent}. A single instance can be reused;
 * polling does not allocate.
 */
public final class NetEvent {
    /** {@link #type}: a client was accepted. */
    public static final int JOINED = 1;
    /** {@link #type}: a client is gone; {@link #reason} gives the cause. Messages it sent earlier may still be unread. */
    public static final int LEFT = 2;

    /** {@link #JOINED} or {@link #LEFT}. */
    public int type;
    /** The client the event is about (remains valid after {@link #LEFT}: id, address, user data, unread messages). */
    public NetClient client;
    /** For {@link #LEFT}: one of the {@code NetServer.LEAVE_} numbers. */
    public int reason;
}
