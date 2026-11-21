// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.InetAddress;
import java.util.ArrayList;

/**
 * UDP server: many clients on one port, one {@link NetChannel} per client, same wire format as {@link Engine#connectNet}.
 * Create with {@link #listen}, call {@link #update()} once per tick, then read {@link #pollEvent} and the clients' channels.
 * {@link #update()}, {@link #send} and {@link #broadcast} neither block nor allocate; accepting a client allocates its
 * buffers (about 110 KB). Use from the single thread that calls {@link #update()}. No window or engine is required; see
 * {@link ServerLoop}.
 * <ul>
 * <li>Clients: a client is accepted when its application id matches and a slot is free ({@link #getMaxClients()});
 *     otherwise it is refused with a reason ({@link NetChannel#getCloseReason()}). Each client is a {@link NetClient} with a
 *     unique id, user data and a {@link NetChannel}. Joins and leaves are reported as {@link NetEvent}. A client that
 *     reconnects from the same address and port replaces its previous connection. A client silent for 10 s
 *     ({@link #setIdleTimeout}) is dropped.</li>
 * <li>Access control: {@link #kick}, {@link #ban} / {@link #unban}, {@link #setMaxClientsPerAddress} and a
 *     {@link ConnectFilter} that receives the address and the connect data of a client before it is accepted.</li>
 * <li>Multicast: {@link #broadcast} and {@link #broadcastReliable} address all clients or one room
 *     ({@link NetClient#setRoom}), optionally excluding one client.</li>
 * <li>Discovery: the server answers {@link ServerBrowser} queries with its name, player count and user data
 *     ({@link #setInfo}).</li>
 * <li>Limits: malformed datagrams and datagrams from unknown sources are dropped and counted; replies to unknown sources
 *     are limited to 200 per second; a client that sends nothing within 3 s of being accepted is dropped. There is no
 *     encryption or authentication; a client is identified by its address and connection token.</li>
 * </ul>
 * Message loop:
 * <pre>
 * NetServer server = NetServer.listen(7777, 0x5A01, 32);
 * NetEvent ev = new NetEvent();
 * NetMessage msg = new NetMessage();
 * while (running) {                                   // every tick
 *     server.update();
 *     while (server.pollEvent(ev)) { ... ev.client joined / left ... }
 *     for (int i = 0; i &lt; server.getClientSlots(); i++) {
 *         NetClient c = server.getClientAt(i);
 *         while (c != null &amp;&amp; c.receive(msg)) { ... handle msg from c ... }
 *     }
 *     server.broadcast(NetServer.ALL, 0, snapshot, 0, snapshotLength);
 * }
 * server.close();
 * </pre>
 * {@link ServerLoop} implements this loop with a fixed tick rate and callbacks.
 */
public final class NetServer {
    /** Opening the socket. */
    public static final int OPENING = 0;
    /** Listening: clients can connect. */
    public static final int RUNNING = 1;
    /** Could not start (the port is taken, a bad address): {@link #getError()} says why. */
    public static final int FAILED = 2;
    /** Closed by {@link #close()}. */
    public static final int CLOSED = 3;

    /** The "room" of {@link #broadcast}: everybody. */
    public static final int ALL = -1;

    /** {@link NetEvent#reason}: the client closed the connection itself. */
    public static final int LEAVE_CLOSED = 0;
    /** {@link NetEvent#reason}: nothing was heard from the client for too long, or it never sent a datagram after it was accepted. */
    public static final int LEAVE_TIMEOUT = 1;
    /** {@link NetEvent#reason}: the server kicked or banned it. */
    public static final int LEAVE_KICKED = 2;
    /** {@link NetEvent#reason}: the same address and port connected again (the client was restarted). */
    public static final int LEAVE_REPLACED = 3;
    /** {@link NetEvent#reason}: the server was closed. */
    public static final int LEAVE_SHUTDOWN = 4;

    /** Decides about a client before it is accepted: see {@link #setConnectFilter}. */
    public interface ConnectFilter {
        /**
         * Says whether this client may come in.
         *
         * @param address the client's address
         * @param port the client's port
         * @param data the connect data sent by {@link NetChannel#setConnectData} is {@code data[offset .. offset + length)}
         *             (a buffer that is reused: copy what you keep)
         * @return 0 to accept, else the reason the client is told: {@link NetChannel#REASON_APP} or more for your own
         *         (anything below that is sent as {@link NetChannel#REASON_REFUSED})
         */
        int check(InetAddress address, int port, byte[] data, int offset, int length);
    }

    private static final long MILLI = 1000000L;
    private static final long HANDSHAKE_NANOS = 3000L * MILLI;
    private static final int REPLIES_PER_SECOND = 200;
    private static final int MAX_NAME = 60;
    private static final int MAX_INFO = 128;

    private final UdpSocket socket;
    private final int appId;
    private final int maxClients;

    private final NetClient[] slots;
    private final int[] bucketHead;
    private final int[] chain;
    private final int bucketMask;
    private final IntIntMap idToSlot;
    private int count;
    private int highWater; // slots at or above this index are empty
    private int nextId = 1;
    private volatile boolean closed;

    private int[] evType = new int[64];
    private NetClient[] evClient = new NetClient[64];
    private int[] evReason = new int[64];
    private int evHead;
    private int evCount;

    private final ArrayList<InetAddress> banned = new ArrayList<InetAddress>();
    private ConnectFilter filter;
    private int maxPerAddress;
    private long idleTimeoutMs = 10000;
    private long keepAliveMs = 500;

    private boolean discovery = true;
    private byte[] infoName = new byte[0];
    private final byte[] infoData = new byte[MAX_INFO];
    private int infoDataLen;

    private long replyTokens = REPLIES_PER_SECOND;
    private long replyNanos;

    private final byte[] out = new byte[UdpSocket.MAX_PACKET];
    private final UdpPacket rx = new UdpPacket();

    private long accepted;
    private long refused;
    private int badPackets;
    private int strayPackets;
    private int queries;

    private NetServer(UdpSocket socket, int appId, int maxClients) {
        this.socket = socket;
        this.appId = appId;
        this.maxClients = maxClients;
        this.slots = new NetClient[maxClients];
        int n = 4;
        while (n < maxClients * 2) {
            n <<= 1;
        }
        this.bucketHead = new int[n];
        for (int i = 0; i < n; i++) {
            bucketHead[i] = -1;
        }
        this.bucketMask = n - 1;
        this.chain = new int[maxClients];
        this.idToSlot = new IntIntMap(maxClients);
        this.replyNanos = System.nanoTime();
    }

    /** {@link #listen(String, int, int, int)} on all local addresses. */
    public static NetServer listen(int port, int appId, int maxClients) {
        return listen(null, port, appId, maxClients);
    }

    /**
     * Starts a server and returns at once; the socket is opened in the background, so a taken port shows as
     * {@link #FAILED} a moment later ({@link #awaitRunning} waits for it, for programs that have nothing else to do).
     * Never throws and never returns null.
     *
     * @param bindHost the local address to listen on, null or empty for all of them
     * @param port the UDP port (0 = any free one, see {@link #getLocalPort()})
     * @param appId the id of your game and its protocol version: clients with another one are refused
     * @param maxClients how many clients at once (1 to 10000)
     */
    public static NetServer listen(String bindHost, int port, int appId, int maxClients) {
        int max = Math.max(1, Math.min(10000, maxClients));
        int queue = Math.max(256, Math.min(4096, max * 4));
        return new NetServer(UdpSocket.listen(bindHost, port, queue), appId, max);
    }

    // ---------------------------------------------------------------- state

    /** {@link #OPENING}, {@link #RUNNING}, {@link #FAILED} or {@link #CLOSED}. */
    public int getState() {
        if (closed) {
            return CLOSED;
        }
        switch (socket.getState()) {
            case UdpSocket.CONNECTING:
                return OPENING;
            case UdpSocket.OPEN:
                return RUNNING;
            case UdpSocket.FAILED:
                return FAILED;
            default:
                return CLOSED;
        }
    }

    /** True while clients can connect. */
    public boolean isRunning() {
        return getState() == RUNNING;
    }

    /** What went wrong, or null unless the state is {@link #FAILED}. */
    public String getError() {
        return socket.getError();
    }

    /**
     * Blocks the calling thread until the server leaves {@link #OPENING}, at most {@code millis}. Not for use in a game loop.
     *
     * @return true when it is {@link #RUNNING}
     */
    public boolean awaitRunning(int millis) {
        return socket.awaitOpen(millis) && !closed;
    }

    /** The port the server listens on (useful when 0 was asked for), -1 while it is not open. */
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    /** The game id that clients must have. */
    public int getAppId() {
        return appId;
    }

    /** The UDP socket under the server (its counters and {@link UdpSocket#setSimulation} for tests). */
    public UdpSocket getSocket() {
        return socket;
    }

    // ---------------------------------------------------------------- settings

    /** After this many milliseconds of silence a client is dropped (default 10000, as {@link NetChannel#setTimeouts}). Affects clients accepted later. */
    public NetServer setIdleTimeout(int millis) {
        idleTimeoutMs = Math.max(1, millis);
        return this;
    }

    /** The keep-alive interval of the channels of clients accepted later (default 500, see {@link NetChannel#setKeepAlive}). */
    public NetServer setKeepAlive(int millis) {
        keepAliveMs = Math.max(1, millis);
        return this;
    }

    /** At most this many clients from one address (0 = no limit, the default). Players behind one NAT share an address. */
    public NetServer setMaxClientsPerAddress(int max) {
        maxPerAddress = Math.max(0, max);
        return this;
    }

    /** Sets (or with null removes) the filter that is asked about every client before it is accepted. */
    public NetServer setConnectFilter(ConnectFilter filter) {
        this.filter = filter;
        return this;
    }

    /**
     * What the server tells those who ask ({@link ServerBrowser}): a name of up to 60 bytes and a block of up to 128 bytes
     * of your own (the map, the game mode, the version; null for none). Without a call the name is empty.
     */
    public NetServer setInfo(String name, byte[] data, int offset, int length) {
        byte[] b = name == null ? new byte[0] : name.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        int n = Math.min(MAX_NAME, b.length);
        while (n > 0 && n < b.length && (b[n] & 0xC0) == 0x80) {
            n--; // do not split a UTF-8 sequence
        }
        byte[] r = new byte[n];
        System.arraycopy(b, 0, r, 0, n);
        infoName = r;
        if (data != null && length > 0 && offset >= 0 && length <= MAX_INFO && offset <= data.length - length) {
            System.arraycopy(data, offset, infoData, 0, length);
            infoDataLen = length;
        } else {
            infoDataLen = 0;
        }
        return this;
    }

    /** {@link #setInfo(String, byte[], int, int)} with a name only. */
    public NetServer setInfo(String name) {
        return setInfo(name, null, 0, 0);
    }

    /** Whether the server answers {@link ServerBrowser} questions (default true). Switch it off for a server that should not be found. */
    public NetServer setDiscovery(boolean on) {
        discovery = on;
        return this;
    }

    // ---------------------------------------------------------------- the tick

    /**
     * Processes received datagrams (accepting and refusing clients, passing messages to the clients' channels), answers
     * queries, repeats reliable messages, sends keep-alives and drops clients that are gone. Call once per tick before
     * reading events and messages. Does not allocate, except for the buffers of an accepted client and when more than 64
     * events are unread.
     */
    public void update() {
        if (closed) {
            return;
        }
        int ss = socket.getState();
        if (ss == UdpSocket.CONNECTING) {
            return;
        }
        if (ss != UdpSocket.OPEN) {
            dropAll(LEAVE_SHUTDOWN); // the socket failed or was closed
            return;
        }
        long now = System.nanoTime();
        long dt = now - replyNanos;
        replyNanos = now;
        replyTokens = Math.min(REPLIES_PER_SECOND, replyTokens + dt * REPLIES_PER_SECOND / 1000000000L);
        while (socket.receive(rx)) {
            dispatch(rx, now);
        }
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c == null) {
                continue;
            }
            NetChannel ch = c.getChannel();
            ch.tick(now);
            int st = ch.getState();
            if (st == NetChannel.CLOSED) {
                remove(c, LEAVE_CLOSED);
            } else if (st == NetChannel.FAILED) {
                remove(c, LEAVE_TIMEOUT);
            } else if (ch.getPacketsReceived() == 0 && now - ch.joinedNanos() > HANDSHAKE_NANOS) {
                ch.closeWith(NetChannel.REASON_UNKNOWN);
                remove(c, LEAVE_TIMEOUT);
            }
        }
    }

    // ---------------------------------------------------------------- events and clients

    /**
     * Copies the oldest event (a client joined or left) into {@code out}. Read the events every tick. Does not allocate.
     *
     * @return false when there is none
     */
    public boolean pollEvent(NetEvent out) {
        if (evCount == 0) {
            return false;
        }
        out.type = evType[evHead];
        out.client = evClient[evHead];
        out.reason = evReason[evHead];
        evClient[evHead] = null;
        evHead = (evHead + 1) % evType.length;
        evCount--;
        return true;
    }

    /** How many clients are connected now. */
    public int getClientCount() {
        return count;
    }

    /** The most clients at once. */
    public int getMaxClients() {
        return maxClients;
    }

    /** The number of places a client can be in: ask {@link #getClientAt} for 0 to this minus 1 (empty places give null). */
    public int getClientSlots() {
        return highWater;
    }

    /** The client in place {@code index}, or null when there is none. The place of a client does not change while it is connected. */
    public NetClient getClientAt(int index) {
        return index >= 0 && index < highWater ? slots[index] : null;
    }

    /** The connected client with this number ({@link NetClient#getId()}), or null. */
    public NetClient getClient(int id) {
        int slot = idToSlot.get(id, -1);
        return slot >= 0 ? slots[slot] : null;
    }

    /** How many connected clients are in a room ({@link NetServer#ALL}: all of them). */
    public int getRoomCount(int room) {
        if (room == ALL) {
            return count;
        }
        int n = 0;
        for (int i = 0; i < highWater; i++) {
            if (slots[i] != null && slots[i].getRoom() == room) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- sending to many

    /**
     * Sends one unreliable message ({@link NetChannel#send}) to the clients of a room, or to {@link #ALL} of them, except
     * the one with the id {@code exceptId} (0 = nobody: ids start at 1).
     *
     * @return to how many clients it was queued
     */
    public int broadcast(int room, int exceptId, byte[] data, int offset, int length) {
        int n = 0;
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c != null && (room == ALL || c.getRoom() == room) && c.getId() != exceptId && c.send(data, offset, length)) {
                n++;
            }
        }
        return n;
    }

    /** Like {@link #broadcast} with {@link NetChannel#sendReliable}: 1 to {@link NetChannel#MAX_RELIABLE} bytes. Clients whose queue is full are skipped; the result counts only the clients to which it was queued. */
    public int broadcastReliable(int room, int exceptId, byte[] data, int offset, int length) {
        int n = 0;
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c != null && (room == ALL || c.getRoom() == room) && c.getId() != exceptId && c.sendReliable(data, offset, length)) {
                n++;
            }
        }
        return n;
    }

    /** Like {@link #broadcastReliable} with {@link NetChannel#sendReliableBig}. */
    public int broadcastReliableBig(int room, int exceptId, byte[] data, int offset, int length) {
        int n = 0;
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c != null && (room == ALL || c.getRoom() == room) && c.getId() != exceptId && c.sendReliableBig(data, offset, length)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- control

    /** {@link #kick(NetClient, int)} with {@link NetChannel#REASON_KICKED}. */
    public void kick(NetClient client) {
        kick(client, NetChannel.REASON_KICKED);
    }

    /**
     * Disconnects the client: it is sent the reason ({@link NetChannel#REASON_KICKED}, or your own from
     * {@link NetChannel#REASON_APP}; shown by {@link NetChannel#getError()} and {@link NetChannel#getCloseReason()}), and
     * a {@link NetEvent#LEFT} with {@link #LEAVE_KICKED} follows. Messages that the server queued for it just before are
     * not guaranteed to arrive. Does nothing for a client that is gone.
     */
    public void kick(NetClient client, int reason) {
        if (client == null || slotOf(client) < 0) {
            return;
        }
        client.getChannel().closeWith(reason <= 0 || reason > 255 ? NetChannel.REASON_KICKED : reason);
        remove(client, LEAVE_KICKED);
    }

    /** Refuses every future connection from this address (not from this port only), and kicks the clients that have it now. */
    public void ban(InetAddress address) {
        if (address == null) {
            return;
        }
        if (!banned.contains(address)) {
            banned.add(address);
        }
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c != null && c.getAddress().equals(address)) {
                kick(c, NetChannel.REASON_BANNED);
            }
        }
    }

    /** Takes an address off the ban list; true when it was on it. */
    public boolean unban(InetAddress address) {
        return banned.remove(address);
    }

    /** True when the address is banned. */
    public boolean isBanned(InetAddress address) {
        for (int i = 0; i < banned.size(); i++) {
            if (banned.get(i).equals(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the server: clients are notified (best effort) and each produces a {@link NetEvent#LEFT} with
     * {@link #LEAVE_SHUTDOWN}; the socket is closed after the queued datagrams have been sent. Returns at once.
     */
    public void close() {
        if (closed) {
            return;
        }
        dropAll(LEAVE_SHUTDOWN);
        closed = true;
        socket.close();
    }

    // ---------------------------------------------------------------- statistics

    /** Clients accepted so far. */
    public long getAcceptedCount() {
        return accepted;
    }

    /** Connection requests turned down so far (wrong appId, full, banned, filtered). */
    public long getRefusedCount() {
        return refused;
    }

    /** Datagrams discarded as not belonging to the protocol (wrong magic, too short, unknown kind). */
    public int getBadPackets() {
        return badPackets;
    }

    /** Datagrams from addresses that are not connected clients. */
    public int getStrayPackets() {
        return strayPackets;
    }

    /** Questions about the server that were answered ({@link ServerBrowser}). */
    public int getQueriesAnswered() {
        return queries;
    }

    // ---------------------------------------------------------------- the wire

    private static int get32(byte[] d, int p) {
        return ((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
    }

    private static void put16(byte[] d, int p, int v) {
        d[p] = (byte) (v >> 8);
        d[p + 1] = (byte) v;
    }

    private static void put32(byte[] d, int p, int v) {
        d[p] = (byte) (v >> 24);
        d[p + 1] = (byte) (v >> 16);
        d[p + 2] = (byte) (v >> 8);
        d[p + 3] = (byte) v;
    }

    /** A header with all numbers zero but the kind and the token (what CLOSE, QUERY and INFO need). */
    private void putSimpleHeader(int kind, int token) {
        for (int i = 0; i < NetChannel.HEADER; i++) {
            out[i] = 0;
        }
        put16(out, 0, NetChannel.MAGIC);
        out[2] = (byte) kind;
        put32(out, 3, token);
    }

    private boolean takeReply() {
        if (replyTokens < 1) {
            return false;
        }
        replyTokens--;
        return true;
    }

    private void refuse(InetAddress address, int port, int token, int reason) {
        if (!takeReply()) {
            return;
        }
        putSimpleHeader(NetChannel.KIND_CLOSE, token);
        out[NetChannel.HEADER] = (byte) reason;
        socket.sendTo(address, port, out, 0, NetChannel.HEADER + 1);
    }

    private void answerQuery(UdpPacket p, int token) {
        if (!discovery) {
            return;
        }
        int wanted = p.length >= NetChannel.HEADER + 4 ? get32(p.data, NetChannel.HEADER) : 0;
        if (wanted != 0 && wanted != appId) {
            return;
        }
        if (!takeReply()) {
            return;
        }
        putSimpleHeader(NetChannel.KIND_INFO, token);
        int pos = NetChannel.HEADER;
        put32(out, pos, appId);
        put16(out, pos + 4, count);
        put16(out, pos + 6, maxClients);
        pos += 8;
        out[pos++] = (byte) infoName.length;
        System.arraycopy(infoName, 0, out, pos, infoName.length);
        pos += infoName.length;
        out[pos++] = (byte) infoDataLen;
        System.arraycopy(infoData, 0, out, pos, infoDataLen);
        pos += infoDataLen;
        socket.sendTo(p.address, p.port, out, 0, pos);
        queries++;
    }

    private void dispatch(UdpPacket p, long now) {
        byte[] d = p.data;
        int n = p.length;
        if (n < NetChannel.HEADER || ((d[0] & 255) << 8 | (d[1] & 255)) != NetChannel.MAGIC) {
            badPackets++;
            return;
        }
        int kind = d[2] & 255;
        int token = get32(d, 3);
        if (kind == NetChannel.KIND_QUERY) {
            answerQuery(p, token);
            return;
        }
        if (kind < NetChannel.KIND_CONNECT || kind > NetChannel.KIND_CLOSE) {
            badPackets++;
            return;
        }
        int slot = find(p.address, p.port);
        NetClient c = slot >= 0 ? slots[slot] : null;
        if (kind == NetChannel.KIND_CONNECT) {
            if (token == 0) {
                badPackets++;
                return;
            }
            if (c != null) {
                if (c.getChannel().token() == token) {
                    c.getChannel().handle(p, now); // a repeated request: answered with ACCEPT again
                    return;
                }
                remove(c, LEAVE_REPLACED); // same address, new token: the client was restarted
            }
            accept(p, token, now);
            return;
        }
        if (c == null) {
            if (kind != NetChannel.KIND_CLOSE && token != 0) {
                strayPackets++;
                refuse(p.address, p.port, token, NetChannel.REASON_UNKNOWN); // informs the client that the server does not know the connection
            } else {
                badPackets++;
            }
            return;
        }
        c.getChannel().handle(p, now);
    }

    private void accept(UdpPacket p, int token, long now) {
        InetAddress a = p.address;
        int port = p.port;
        int dataOff = NetChannel.HEADER + 4;
        int app = p.length >= dataOff ? get32(p.data, NetChannel.HEADER) : 0;
        int reason = 0;
        if (isBanned(a)) {
            reason = NetChannel.REASON_BANNED;
        } else if (app != appId || p.length < dataOff) {
            reason = NetChannel.REASON_REFUSED;
        } else if (count >= maxClients || (maxPerAddress > 0 && countAddress(a) >= maxPerAddress)) {
            reason = NetChannel.REASON_FULL;
        } else if (filter != null) {
            int len = Math.min(p.length - dataOff, NetChannel.MAX_CONNECT_DATA);
            try {
                reason = filter.check(a, port, p.data, dataOff, len);
            } catch (RuntimeException e) {
                reason = NetChannel.REASON_REFUSED; // a failing filter refuses the client
            }
            if (reason != 0 && (reason < NetChannel.REASON_APP || reason > 255)) {
                reason = NetChannel.REASON_REFUSED;
            }
        }
        if (reason != 0) {
            refused++;
            refuse(a, port, token, reason);
            return;
        }
        int slot = 0;
        while (slots[slot] != null) {
            slot++; // there is a free one: count < maxClients
        }
        NetChannel ch = new NetChannel(socket, a, port, appId, token, now);
        ch.setTimeouts(8000, (int) idleTimeoutMs);
        ch.setKeepAlive((int) keepAliveMs);
        int len = Math.min(Math.max(0, p.length - dataOff), NetChannel.MAX_CONNECT_DATA);
        NetClient c = new NetClient(nextId++, ch, a, port, p.data, dataOff, len, now);
        slots[slot] = c;
        if (slot >= highWater) {
            highWater = slot + 1;
        }
        int b = bucket(a, port);
        chain[slot] = bucketHead[b];
        bucketHead[b] = slot;
        idToSlot.put(c.getId(), slot);
        count++;
        accepted++;
        ch.sendAccept();
        event(NetEvent.JOINED, c, 0);
    }

    // ---------------------------------------------------------------- the client table

    private int bucket(InetAddress a, int port) {
        int h = a.hashCode() * 31 + port;
        h *= 0x9E3779B9;
        return (h ^ (h >>> 15)) & bucketMask;
    }

    private int find(InetAddress a, int port) {
        int s = bucketHead[bucket(a, port)];
        while (s >= 0) {
            NetClient c = slots[s];
            if (c.getPort() == port && c.getAddress().equals(a)) {
                return s;
            }
            s = chain[s];
        }
        return -1;
    }

    private int slotOf(NetClient client) {
        int slot = idToSlot.get(client.getId(), -1);
        return slot >= 0 && slots[slot] == client ? slot : -1;
    }

    private int countAddress(InetAddress a) {
        int n = 0;
        for (int i = 0; i < highWater; i++) {
            if (slots[i] != null && slots[i].getAddress().equals(a)) {
                n++;
            }
        }
        return n;
    }

    private void remove(NetClient c, int reason) {
        int slot = slotOf(c);
        if (slot < 0) {
            return;
        }
        int b = bucket(c.getAddress(), c.getPort());
        int s = bucketHead[b];
        int prev = -1;
        while (s >= 0 && s != slot) {
            prev = s;
            s = chain[s];
        }
        if (s == slot) {
            if (prev < 0) {
                bucketHead[b] = chain[s];
            } else {
                chain[prev] = chain[s];
            }
        }
        idToSlot.remove(c.getId());
        slots[slot] = null;
        while (highWater > 0 && slots[highWater - 1] == null) {
            highWater--;
        }
        count--;
        c.markGone();
        event(NetEvent.LEFT, c, reason);
    }

    private void dropAll(int reason) {
        for (int i = 0; i < highWater; i++) {
            NetClient c = slots[i];
            if (c != null) {
                if (reason == LEAVE_SHUTDOWN && !closed && socket.isOpen()) {
                    c.getChannel().closeWith(NetChannel.REASON_NORMAL);
                }
                remove(c, reason);
            }
        }
    }

    private void event(int type, NetClient c, int reason) {
        if (evCount == evType.length) {
            int n = evType.length * 2; // unread events are never discarded
            int[] t = new int[n];
            NetClient[] cl = new NetClient[n];
            int[] r = new int[n];
            for (int i = 0; i < evCount; i++) {
                int j = (evHead + i) % evType.length;
                t[i] = evType[j];
                cl[i] = evClient[j];
                r[i] = evReason[j];
            }
            evType = t;
            evClient = cl;
            evReason = r;
            evHead = 0;
        }
        int slot = (evHead + evCount) % evType.length;
        evType[slot] = type;
        evClient[slot] = c;
        evReason[slot] = reason;
        evCount++;
    }
}
