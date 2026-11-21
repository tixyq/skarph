// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** {@link ServerHandler} with empty methods. */
public abstract class ServerAdapter implements ServerHandler {
    public void onStart(NetServer server) {
    }

    public void onJoin(NetServer server, NetClient client) {
    }

    public void onLeave(NetServer server, NetClient client, int reason) {
    }

    public void onMessage(NetServer server, NetClient client, NetMessage message) {
    }

    public void onTick(NetServer server, long tick, float dt) {
    }

    public void onStop(NetServer server) {
    }
}
