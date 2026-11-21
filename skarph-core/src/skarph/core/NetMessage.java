// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Holder for one message received by a {@link NetChannel}, filled by {@link NetChannel#receive}. A single instance can be
 * reused; polling does not allocate. Only the first {@link #length} bytes of {@link #data} are valid.
 * The default holder takes messages up to {@link NetChannel#MAX_PAYLOAD} bytes; receiving messages sent with
 * {@link NetChannel#sendReliableBig} requires a holder created with {@link #NetMessage(int)}.
 */
public final class NetMessage {
    /** The message (capacity {@link NetChannel#MAX_PAYLOAD} or what the constructor was given); read only {@link #length} bytes. */
    public final byte[] data;
    /** Number of valid bytes in {@link #data}. */
    public int length;
    /** True for a message sent with {@link NetChannel#sendReliable}, false for one sent with {@link NetChannel#send}. */
    public boolean reliable;

    /** A holder for messages up to {@link NetChannel#MAX_PAYLOAD} bytes. */
    public NetMessage() {
        this(NetChannel.MAX_PAYLOAD);
    }

    /**
     * A holder with room for {@code capacity} bytes (never less than {@link NetChannel#MAX_PAYLOAD}); use
     * {@link NetChannel#MAX_BIG} for the biggest messages there are. A message that does not fit is discarded by
     * {@link NetChannel#receive} and counted in {@link NetChannel#getBigDropped()}.
     */
    public NetMessage(int capacity) {
        data = new byte[Math.max(capacity, NetChannel.MAX_PAYLOAD)];
    }
}
