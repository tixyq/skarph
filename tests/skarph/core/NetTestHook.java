// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Gives NetTest (default package) the package-private "other side" of a NetChannel, which stands in for the server. */
public final class NetTestHook {
    private NetTestHook() {
    }

    public static void setSequence(NetChannel c, int seq) {
        c.setLocalSequence(seq);
    }

    public static NetChannel accept(UdpSocket socket, int appId, boolean ownsSocket) {
        return NetChannel.acceptOn(socket, appId, ownsSocket);
    }
}
