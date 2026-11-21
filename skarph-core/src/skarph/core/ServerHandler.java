// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Callbacks of a {@link ServerLoop}. Everything is called on the loop's thread, one after the other, so a
 * handler needs no locks for its own data. Extend {@link ServerAdapter} to override selected methods.
 */
public interface ServerHandler {
    /** The server is listening: initialize the game state. */
    void onStart(NetServer server);

    /** A client was accepted. */
    void onJoin(NetServer server, NetClient client);

    /** A client is gone ({@code reason} is one of the {@code NetServer.LEAVE_} numbers); all its earlier messages have been delivered. */
    void onLeave(NetServer server, NetClient client, int reason);

    /** A message arrived from a client ({@code message} is reused: copy what you keep). */
    void onMessage(NetServer server, NetClient client, NetMessage message);

    /** Once per tick, after the messages: advance the game state and send updates. {@code tick} counts from 1, {@code dt} is the tick length in seconds. */
    void onTick(NetServer server, long tick, float dt);

    /** The loop ended; called after the clients have been notified and their leaves delivered: save state, release resources. */
    void onStop(NetServer server);
}
