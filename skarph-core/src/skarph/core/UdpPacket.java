// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.InetAddress;

/**
 * Holder for one received datagram, filled by {@link UdpSocket#receive}. A single instance can be reused; polling does not
 * allocate. Only the first {@link #length} bytes of {@link #data} are valid.
 */
public final class UdpPacket {
    /** The payload (capacity {@link UdpSocket#MAX_PACKET}); read only {@link #length} bytes. */
    public final byte[] data = new byte[UdpSocket.MAX_PACKET];
    /** Number of valid bytes in {@link #data}. */
    public int length;
    /** Sender address. */
    public InetAddress address;
    /** The sender's port. */
    public int port;
    /** {@link System#nanoTime()} at the moment the datagram arrived (taken on the network thread). */
    public long receivedNanos;
}
