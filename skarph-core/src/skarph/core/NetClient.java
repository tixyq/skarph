// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.InetAddress;

/**
 * One client of a {@link NetServer}: identity, connection and application data. Created by the server when a client is
 * accepted; the object remains valid after the client leaves so that remaining messages can be read. Use from the thread
 * that calls {@link NetServer#update()}. The send and receive methods forward to the client's {@link NetChannel}.
 */
public final class NetClient {
    private final int id;
    private final NetChannel channel;
    private final InetAddress address;
    private final int port;
    private final byte[] connectData;
    private final int connectDataLength;
    private final long joinedNanos;
    private int room;
    private boolean present = true;
    private Object userData;

    NetClient(int id, NetChannel channel, InetAddress address, int port, byte[] data, int dataOffset, int dataLength, long now) {
        this.id = id;
        this.channel = channel;
        this.address = address;
        this.port = port;
        this.connectDataLength = Math.max(0, Math.min(dataLength, NetChannel.MAX_CONNECT_DATA));
        this.connectData = connectDataLength > 0 ? new byte[connectDataLength] : null;
        if (connectData != null) {
            System.arraycopy(data, dataOffset, connectData, 0, connectDataLength);
        }
        this.joinedNanos = now;
    }

    /** Unique client id, assigned in order of arrival starting at 1; not reused while the server runs. */
    public int getId() {
        return id;
    }

    /** The client's connection (its {@link NetChannel#receive}, {@link NetChannel#getRttMillis()} ...). */
    public NetChannel getChannel() {
        return channel;
    }

    /** The client's address. */
    public InetAddress getAddress() {
        return address;
    }

    /** The client's port. */
    public int getPort() {
        return port;
    }

    /** True until the client leaves (or is kicked): see {@link NetEvent#LEFT}. */
    public boolean isPresent() {
        return present && channel.isConnected();
    }

    /** Application object associated with this client; not used by the server. */
    public Object getUserData() {
        return userData;
    }

    /** Sets {@link #getUserData()}. */
    public void setUserData(Object userData) {
        this.userData = userData;
    }

    /** The room the client is in (default 0): {@link NetServer#broadcast} can address a room. */
    public int getRoom() {
        return room;
    }

    /** Puts the client in a room (any number; {@link NetServer#ALL} is not a room). */
    public void setRoom(int room) {
        this.room = room;
    }

    /** Milliseconds since the client was accepted. */
    public int getConnectedMillis() {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - joinedNanos) / 1000000L);
    }

    /** Length of the connect data the client sent with its request ({@link NetChannel#setConnectData}), 0 when none. */
    public int getConnectDataLength() {
        return connectDataLength;
    }

    /** Copies the connect data into {@code out} (at most what fits) and returns the number of bytes copied. */
    public int getConnectData(byte[] out, int offset) {
        int n = Math.min(connectDataLength, out.length - offset);
        if (n <= 0) {
            return 0;
        }
        System.arraycopy(connectData, 0, out, offset, n);
        return n;
    }

    /** {@link NetChannel#send}. */
    public boolean send(byte[] data, int offset, int length) {
        return channel.send(data, offset, length);
    }

    /** {@link NetChannel#sendReliable}. */
    public boolean sendReliable(byte[] data, int offset, int length) {
        return channel.sendReliable(data, offset, length);
    }

    /** {@link NetChannel#sendReliableBig}. */
    public boolean sendReliableBig(byte[] data, int offset, int length) {
        return channel.sendReliableBig(data, offset, length);
    }

    /** {@link NetChannel#receive}: the oldest message this client sent. */
    public boolean receive(NetMessage out) {
        return channel.receive(out);
    }

    /** {@link NetChannel#getRttMillis()}. */
    public int getRttMillis() {
        return channel.getRttMillis();
    }

    /** {@link NetChannel#getPacketLoss()}. */
    public float getPacketLoss() {
        return channel.getPacketLoss();
    }

    void markGone() {
        present = false;
    }

    @Override
    public String toString() {
        return "client #" + id + " " + address + ":" + port;
    }
}
