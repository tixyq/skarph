// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.InetAddress;
import java.util.Random;

/**
 * A connection to one peer over a {@link UdpSocket}. {@link #update()} must be called once per logic tick from the game
 * thread; it processes received datagrams, sends acknowledgements and keep-alives, repeats unconfirmed reliable messages
 * and detects a lost connection. No method blocks or allocates.
 * <ul>
 * <li>Connection: the client sends CONNECT with the application id until ACCEPT arrives ({@link #CONNECTING}, then
 *     {@link #CONNECTED}). A random token in every datagram binds datagrams to the connection; it is not a security
 *     measure. The channel ends in {@link #CLOSED} (closed locally or by the peer) or {@link #FAILED} (timeout, refusal,
 *     socket failure; see {@link #getError()}).</li>
 * <li>{@link #send}: unreliable and sequenced, one message per datagram, at most {@link #MAX_PAYLOAD} bytes. Late and
 *     duplicate datagrams are discarded.</li>
 * <li>{@link #sendReliable}: reliable and ordered, at most {@link #MAX_RELIABLE} bytes. Each message is delivered exactly
 *     once in sending order and is repeated every 1.5 round-trip times until confirmed. At most {@link #RELIABLE_QUEUE}
 *     messages may be unconfirmed.</li>
 * <li>{@link #sendReliableBig}: reliable and ordered, at most {@link #MAX_BIG} bytes. The message is split into parts of
 *     the reliable stream and delivered as one message to a {@link NetMessage} of sufficient capacity.</li>
 * <li>Measurement: every datagram carries the number of the newest datagram received and a 32-bit mask of the preceding
 *     ones. {@link #getRttMillis()} and {@link #getPacketLoss()} are derived from them; the peer's processing delay is
 *     subtracted from the round-trip time. An idle channel sends a keep-alive every 500 ms. A send rate of 10 to 30
 *     datagrams per second is recommended.</li>
 * </ul>
 * The wire format is specified in docs/MANUAL.md. There is no encryption or authentication.
 * A server creates one channel per client ({@link NetClient#getChannel()}) and drives it itself; {@link #update()} has no
 * effect on such a channel.
 */
public final class NetChannel {
    /** The connection is not established yet; reliable messages are queued. */
    public static final int CONNECTING = 0;
    /** The connection is established. */
    public static final int CONNECTED = 1;
    /** Closed here by {@link #close()} or by the other side. */
    public static final int CLOSED = 2;
    /** The connection failed (timeout, refusal or socket failure); see {@link #getError()}. */
    public static final int FAILED = 3;

    /** Bytes every datagram of the channel starts with. */
    public static final int HEADER = 19;
    /** Maximum size of an unreliable message in bytes; such a message fills the datagram. */
    public static final int MAX_PAYLOAD = UdpSocket.MAX_PACKET - HEADER - 1;
    /** The largest reliable message in bytes. */
    public static final int MAX_RELIABLE = 512;
    /** How many reliable messages can wait for their acknowledgement at the same time. */
    public static final int RELIABLE_QUEUE = 64;
    /** Maximum size of a message sent with {@link #sendReliableBig}: the entire reliable queue. */
    public static final int MAX_BIG = RELIABLE_QUEUE * MAX_RELIABLE;
    /** The largest block of connect data: see {@link #setConnectData}. */
    public static final int MAX_CONNECT_DATA = 256;
    /** How many received messages wait in the inbox for {@link #receive}. */
    public static final int INBOX = 64;

    /** First two bytes of every datagram: "SK". */
    public static final int MAGIC = 0x534B;
    /** Datagram kinds (byte 2 of the header). */
    public static final int KIND_CONNECT = 1, KIND_ACCEPT = 2, KIND_DATA = 3, KIND_CLOSE = 4;
    /** More datagram kinds: a question about a server ({@link ServerBrowser}) and the answer to it. */
    public static final int KIND_QUERY = 5, KIND_INFO = 6;
    /**
     * Reasons in a CLOSE datagram: closed normally; refused (another appId); the server is full; kicked out; banned; the
     * server does not know the connection (it was restarted, or dropped the client): connect again. Reasons from
     * {@link #REASON_APP} up are the game's own (a wrong password, a version that is too old, ...).
     */
    public static final int REASON_NORMAL = 0, REASON_REFUSED = 1, REASON_FULL = 2, REASON_KICKED = 3, REASON_BANNED = 4,
            REASON_UNKNOWN = 5;
    /** The first reason number a game may use for its own purposes (up to 255); see {@link NetServer.ConnectFilter}. */
    public static final int REASON_APP = 16;
    /** Bit 15 of the length of a reliable message in a datagram: "more parts of the same big message follow". */
    private static final int MORE = 0x8000;

    private static final long MILLI = 1000000L;
    private static final int WINDOW = 64;
    private static final int ACK_EVERY = 16;

    private final UdpSocket socket;
    private final boolean ownsSocket;
    private final boolean passive;
    private final int appId;
    private final InetAddress peerAddress; // not null: this is the server's end of one client, sent through the shared socket
    private final int peerPort;

    private int state = CONNECTING;
    private String error;
    private int token;
    private long startNanos;
    private long lastRecvNanos;
    private long lastSendNanos;
    private long lastConnectNanos;
    private boolean connectSent;
    private long connectTimeoutMs = 8000;
    private long idleTimeoutMs = 10000;
    private long keepAliveMs = 500;
    private long connectRetryMs = 250;
    private int closeReason = -1;
    private byte[] connectData;
    private int connectDataLen;

    // numbering of datagrams, acknowledgements, measuring
    private int localSeq = 1;
    private boolean haveRemote;
    private int remoteSeq;
    private int recvBits;
    private long remoteSeqNanos;
    private final long[] sentNanos = new long[WINDOW];
    private final int[] sentSeq = new int[WINDOW];
    private final boolean[] sentValid = new boolean[WINDOW];
    private final boolean[] sentAcked = new boolean[WINDOW];
    private boolean haveRtt;
    private float rtt;
    private float loss;

    // reliable messages we send (ring, oldest first) and the id we wait for from the other side
    private final byte[][] relData = new byte[RELIABLE_QUEUE][MAX_RELIABLE];
    private final int[] relLen = new int[RELIABLE_QUEUE];
    private final long[] relSent = new long[RELIABLE_QUEUE];
    private final boolean[] relSentOnce = new boolean[RELIABLE_QUEUE];
    private final boolean[] relMore = new boolean[RELIABLE_QUEUE];
    private int relHead;
    private int relCount;
    private int relHeadId;
    private int relExpected;
    private boolean ackPending;
    private int receivedSinceSend;

    // received messages
    private final byte[][] inData = new byte[INBOX][MAX_PAYLOAD];
    private final int[] inLen = new int[INBOX];
    private final boolean[] inRel = new boolean[INBOX];
    private final boolean[] inBig = new boolean[INBOX];
    private byte[] bigData; // the big message being put together, or waiting for receive(); allocated by the first one
    private int bigLen;
    private boolean bigReady;
    private int bigDropped;
    private int inHead;
    private int inCount;

    private final byte[] out = new byte[UdpSocket.MAX_PACKET];
    private final UdpPacket rx = new UdpPacket();

    private long packetsSent;
    private long packetsReceived;
    private long bytesSent;
    private long bytesReceived;
    private int badPackets;
    private int duplicates;
    private int inboxDropped;

    /**
     * Creates a client channel over a socket supplied by the caller; the channel does not close it. The connection starts
     * when the socket is open. {@code appId} identifies the application and protocol version; a server refuses other values.
     */
    public NetChannel(UdpSocket socket, int appId) {
        this(socket, appId, false, false);
    }

    private NetChannel(UdpSocket socket, int appId, boolean ownsSocket, boolean passive) {
        this(socket, appId, ownsSocket, passive, null, 0);
    }

    /** The server's end of one accepted client, talking through the server's (shared, listening) socket. */
    NetChannel(UdpSocket serverSocket, InetAddress address, int port, int appId, int token, long now) {
        this(serverSocket, appId, false, true, address, port);
        this.token = token;
        this.state = CONNECTED;
        this.startNanos = now;
        this.lastRecvNanos = now;
        this.lastSendNanos = now;
    }

    private NetChannel(UdpSocket socket, int appId, boolean ownsSocket, boolean passive, InetAddress peerAddress, int peerPort) {
        this.socket = socket;
        this.appId = appId;
        this.ownsSocket = ownsSocket;
        this.passive = passive;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.startNanos = System.nanoTime();
        this.lastRecvNanos = startNanos;
        this.lastSendNanos = startNanos;
        if (!passive) {
            Random r = new Random(startNanos ^ ((long) System.identityHashCode(this) << 32));
            int t;
            do {
                t = r.nextInt();
            } while (t == 0);
            token = t;
        }
    }

    /**
     * Connects to {@code host:port} in the background and returns at once; the channel owns its socket and closes it with
     * {@link #close()}. Never throws and never returns null: a problem shows as {@link #FAILED} after the next
     * {@link #update()}. {@code Engine.connectNet} additionally closes the socket when the program ends.
     */
    public static NetChannel connect(String host, int port, int appId) {
        return new NetChannel(UdpSocket.connect(host, port), appId, true, false);
    }

    /** The connection token. */
    int token() {
        return token;
    }

    /** Creation time in {@link System#nanoTime()} units. */
    long joinedNanos() {
        return startNanos;
    }

    /** Sends ACCEPT to the client. */
    void sendAccept() {
        sendSimple(KIND_ACCEPT, 0, false);
    }

    /** Sets the number of the next datagram. */
    void setLocalSequence(int seq) {
        localSeq = seq & 0xFFFF;
        if (localSeq == 0) {
            localSeq = 1;
        }
    }

    /** Server side of a single peer over a connected socket: waits for CONNECT and answers ACCEPT. {@link NetServer} serves multiple clients. */
    static NetChannel acceptOn(UdpSocket socket, int appId, boolean ownsSocket) {
        return new NetChannel(socket, appId, ownsSocket, true);
    }

    /** Waiting time for the first answer, then the silence after which a connected channel fails (defaults 8000 and 10000 ms). */
    public NetChannel setTimeouts(int connectMillis, int idleMillis) {
        connectTimeoutMs = Math.max(1, connectMillis);
        idleTimeoutMs = Math.max(1, idleMillis);
        return this;
    }

    /**
     * A block of up to {@link #MAX_CONNECT_DATA} bytes that is sent to the server with the connection request (a name, a
     * password, a ticket); the server reads it in {@link NetServer.ConnectFilter} and {@link NetClient#getConnectData}. Call it
     * right after the channel is made, before the first {@link #update()}. Not encrypted. Returns false for a bad range.
     */
    public boolean setConnectData(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || length > MAX_CONNECT_DATA || offset > data.length - length) {
            return false;
        }
        if (connectData == null) {
            connectData = new byte[MAX_CONNECT_DATA];
        }
        System.arraycopy(data, offset, connectData, 0, length);
        connectDataLen = length;
        return true;
    }

    /** After this many milliseconds without sending, a tiny keep-alive datagram goes out (default 500). */
    public NetChannel setKeepAlive(int millis) {
        keepAliveMs = Math.max(1, millis);
        return this;
    }

    /** {@link #CONNECTING}, {@link #CONNECTED}, {@link #CLOSED} or {@link #FAILED}. */
    public int getState() {
        return state;
    }

    /** True while the other side answers. */
    public boolean isConnected() {
        return state == CONNECTED;
    }

    /** What went wrong, or null unless the state is {@link #FAILED}. */
    public String getError() {
        return state == FAILED ? error : null;
    }

    /** The UDP socket under the channel. */
    public UdpSocket getSocket() {
        return socket;
    }

    // ---------------------------------------------------------------- the tick

    /**
     * Processes received datagrams, sends acknowledgements and keep-alives, repeats unconfirmed reliable messages and detects
     * a lost connection. Call once per logic tick, before reading messages. Does not allocate.
     */
    public void update() {
        if (peerAddress != null || state == CLOSED || state == FAILED) {
            return; // the server's end of a client is driven by the NetServer
        }
        long now = System.nanoTime();
        while (state != CLOSED && state != FAILED && socket.receive(rx)) {
            handle(rx, now);
        }
        tick(now);
    }

    /** What update() does after the datagrams were handled; the server calls it for each of its clients. */
    void tick(long now) {
        if (state == CLOSED || state == FAILED) {
            return;
        }
        int ss = socket.getState();
        if (ss == UdpSocket.FAILED) {
            fail(socket.getError());
            return;
        }
        if (ss == UdpSocket.CLOSED) {
            fail("the socket was closed");
            return;
        }
        if (state == CONNECTING) {
            if (now - startNanos > connectTimeoutMs * MILLI) {
                fail(passive ? "nobody connected" : "no answer from the server");
            } else if (!passive && ss == UdpSocket.OPEN && (!connectSent || now - lastConnectNanos >= connectRetryMs * MILLI)) {
                sendConnect(now);
            }
            return;
        }
        if (now - lastRecvNanos > idleTimeoutMs * MILLI) {
            fail("connection timed out: nothing heard for " + idleTimeoutMs + " ms");
            return;
        }
        // acknowledge when many datagrams were received without sending: the mask covers 32
        if (ackPending || receivedSinceSend >= ACK_EVERY || hasDueReliable(now) || now - lastSendNanos >= keepAliveMs * MILLI) {
            transmit(null, 0, 0, now);
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends one unreliable message in its own datagram, together with any reliable messages that are due.
     *
     * @return false when it was not sent: not {@link #CONNECTED}, a bad range, or more than {@link #MAX_PAYLOAD} bytes.
     *         A true result does not indicate delivery.
     */
    public boolean send(byte[] data, int offset, int length) {
        if (state != CONNECTED || data == null || offset < 0 || length < 0 || length > MAX_PAYLOAD
                || offset > data.length - length) {
            return false;
        }
        return transmit(data, offset, length, System.nanoTime());
    }

    /**
     * Queues a reliable message: it is sent with the next datagram, repeated until the other side confirms it, and
     * delivered there exactly once and in order.
     *
     * @return false when it was not queued: closed or failed, a length that is not 1..{@link #MAX_RELIABLE}, or
     *         {@link #RELIABLE_QUEUE} messages are already waiting for their confirmation
     */
    public boolean sendReliable(byte[] data, int offset, int length) {
        if ((state != CONNECTING && state != CONNECTED) || data == null || offset < 0 || length < 1
                || length > MAX_RELIABLE || offset > data.length - length || relCount == RELIABLE_QUEUE) {
            return false;
        }
        int slot = (relHead + relCount) % RELIABLE_QUEUE;
        System.arraycopy(data, offset, relData[slot], 0, length);
        relLen[slot] = length;
        relMore[slot] = false;
        relSentOnce[slot] = false;
        relCount++;
        return true;
    }

    /**
     * Like {@link #sendReliable} for a message of 1 to {@link #MAX_BIG} bytes. The message is split into parts of up to
     * {@link #MAX_RELIABLE} bytes, each occupying a slot of the reliable queue, and is delivered as one message, exactly
     * once and in order. The call is atomic: it succeeds only if the free queue space ({@link #getReliableFree()}) holds
     * all parts. The receiver needs a {@link NetMessage} of sufficient capacity and receives the next big message only
     * after it has read the previous one. Larger data should be transferred with HTTP.
     *
     * @return false when it was not queued: closed or failed, a bad range, too long, or not enough room in the queue
     */
    public boolean sendReliableBig(byte[] data, int offset, int length) {
        if (length <= MAX_RELIABLE) {
            return sendReliable(data, offset, length);
        }
        if ((state != CONNECTING && state != CONNECTED) || data == null || offset < 0 || length > MAX_BIG
                || offset > data.length - length) {
            return false;
        }
        int parts = (length + MAX_RELIABLE - 1) / MAX_RELIABLE;
        if (parts > RELIABLE_QUEUE - relCount) {
            return false;
        }
        for (int i = 0; i < parts; i++) {
            int n = Math.min(MAX_RELIABLE, length - i * MAX_RELIABLE);
            int slot = (relHead + relCount) % RELIABLE_QUEUE;
            System.arraycopy(data, offset + i * MAX_RELIABLE, relData[slot], 0, n);
            relLen[slot] = n;
            relMore[slot] = i < parts - 1;
            relSentOnce[slot] = false;
            relCount++;
        }
        return true;
    }

    /** How many more reliable messages (or parts of a big one) fit in the queue now. */
    public int getReliableFree() {
        return RELIABLE_QUEUE - relCount;
    }

    /** How many reliable messages have not been confirmed yet. */
    public int getReliablePending() {
        return relCount;
    }

    // ---------------------------------------------------------------- receiving

    /**
     * Copies the oldest received message (reliable or not, in the order of arrival) into {@code out}.
     * Does not allocate.
     *
     * @return false when there is none
     */
    public boolean receive(NetMessage out) {
        while (inCount > 0) {
            int n = inLen[inHead];
            boolean big = inBig[inHead];
            byte[] from = big ? bigData : inData[inHead];
            boolean fits = n <= out.data.length;
            if (fits) {
                System.arraycopy(from, 0, out.data, 0, n);
                out.length = n;
                out.reliable = inRel[inHead];
            } else {
                bigDropped++; // holder too small: the message is discarded
            }
            if (big) {
                bigReady = false; // the next big message may be put together now
                inBig[inHead] = false;
            }
            inHead = (inHead + 1) % INBOX;
            inCount--;
            if (fits) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- closing

    /**
     * Tells the other side (three CLOSE datagrams, best effort) and ends the channel; the state becomes
     * {@link #CLOSED}. A channel made by {@link #connect} or {@code engine.connectNet} also closes its socket.
     * Does nothing when already closed or failed.
     */
    public void close() {
        closeWith(REASON_NORMAL);
    }

    /** Ends the channel and tells the other side the reason (the server kicks with it). */
    void closeWith(int reason) {
        if (state == CLOSED || state == FAILED) {
            return;
        }
        if (token != 0 && socket.isOpen()) {
            for (int i = 0; i < 3; i++) {
                sendSimple(KIND_CLOSE, reason, true);
            }
        }
        state = CLOSED;
        if (ownsSocket) {
            socket.close();
        }
    }

    /**
     * Why the other side ended the connection, when it said so: one of the {@code REASON_} numbers (also those of
     * the game, from {@link #REASON_APP}); -1 when it did not (a timeout, a normal end by us, still connected).
     */
    public int getCloseReason() {
        return closeReason;
    }

    private void fail(String message) {
        error = message != null ? message : "failed";
        state = FAILED;
        if (ownsSocket) {
            socket.close();
        }
    }

    // ---------------------------------------------------------------- measuring and statistics

    /** Smoothed round trip time in milliseconds, -1 until the first datagram was confirmed. */
    public int getRttMillis() {
        return haveRtt ? Math.round(rtt) : -1;
    }

    /** Share of the datagrams sent recently that were not confirmed, 0..1 (a moving average over the last ~50 datagrams). */
    public float getPacketLoss() {
        return loss;
    }

    /** Milliseconds since the last valid datagram arrived. */
    public int getSilenceMillis() {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - lastRecvNanos) / MILLI);
    }

    /** Datagrams of this channel sent (CONNECT and CLOSE excluded). */
    public long getPacketsSent() {
        return packetsSent;
    }

    /** Valid datagrams of this channel received. */
    public long getPacketsReceived() {
        return packetsReceived;
    }

    /** Bytes of the datagrams sent (with the channel's header, without IP and UDP headers). */
    public long getBytesSent() {
        return bytesSent;
    }

    /** Bytes of the valid datagrams received. */
    public long getBytesReceived() {
        return bytesReceived;
    }

    /** Datagrams discarded as not belonging to the channel (wrong magic, token or format). */
    public int getBadPackets() {
        return badPackets;
    }

    /** Datagrams discarded as duplicates or too old. */
    public int getDuplicates() {
        return duplicates;
    }

    /** Big messages discarded because the {@link NetMessage} passed to {@link #receive} was too small. */
    public int getBigDropped() {
        return bigDropped;
    }

    /** Unreliable messages discarded because the inbox was full. */
    public int getInboxDropped() {
        return inboxDropped;
    }

    // ---------------------------------------------------------------- the wire

    private static int get16(byte[] d, int p) {
        return ((d[p] & 255) << 8) | (d[p + 1] & 255);
    }

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

    private void putHeader(int kind, int seq, int ack, int ackBits, int ackDelay, int relAck) {
        put16(out, 0, MAGIC);
        out[2] = (byte) kind;
        put32(out, 3, token);
        put16(out, 7, seq);
        put16(out, 9, ack);
        put32(out, 11, ackBits);
        put16(out, 15, ackDelay);
        put16(out, 17, relAck);
    }

    private void sendConnect(long now) {
        putHeader(KIND_CONNECT, 0, 0, 0, 0, 0);
        put32(out, HEADER, appId);
        int n = HEADER + 4;
        if (connectDataLen > 0) {
            System.arraycopy(connectData, 0, out, n, connectDataLen);
            n += connectDataLen;
        }
        socket.send(out, 0, n);
        lastConnectNanos = now;
        connectSent = true;
    }

    /** CONNECT's answers: a header and, for CLOSE, the reason. */
    private void sendSimple(int kind, int reason, boolean withReason) {
        putHeader(kind, 0, 0, 0, 0, 0);
        int n = HEADER;
        if (withReason) {
            out[n++] = (byte) reason;
        }
        rawSend(n);
    }

    /** A datagram of the connection: to the connected peer, or through the server's socket to this client. */
    private boolean rawSend(int n) {
        return peerAddress != null ? socket.sendTo(peerAddress, peerPort, out, 0, n) : socket.send(out, 0, n);
    }

    private boolean hasDueReliable(long now) {
        long again = resendNanos();
        for (int i = 0; i < relCount; i++) {
            int idx = (relHead + i) % RELIABLE_QUEUE;
            if (!relSentOnce[idx] || now - relSent[idx] >= again) {
                return true;
            }
        }
        return false;
    }

    private long resendNanos() {
        float ms = haveRtt ? rtt * 1.5f : 200f;
        return (long) Math.max(60f, Math.min(1000f, ms)) * MILLI;
    }

    /** Builds and queues one DATA datagram: header, the reliable messages that are due, then the unreliable payload. */
    private boolean transmit(byte[] payload, int off, int len, long now) {
        int seq = localSeq;
        localSeq = (localSeq + 1) & 0xFFFF;
        if (localSeq == 0) {
            localSeq = 1; // 0 means "nothing received yet" in the ack field
        }
        int slot = seq & (WINDOW - 1);
        if (sentValid[slot]) { // the datagram 64 back is final now: confirmed or lost
            loss = loss * 0.98f + (sentAcked[slot] ? 0f : 0.02f);
        }
        sentValid[slot] = true;
        sentAcked[slot] = false;
        sentSeq[slot] = seq;
        sentNanos[slot] = now;

        int delay = haveRemote ? (int) Math.min(65535L, Math.max(0L, (now - remoteSeqNanos) / MILLI)) : 0;
        putHeader(KIND_DATA, seq, haveRemote ? remoteSeq : 0, recvBits, delay, relExpected);
        int pos = HEADER;
        int countPos = pos++;
        int count = 0;
        int budget = UdpSocket.MAX_PACKET - pos - len;
        long again = resendNanos();
        for (int i = 0; i < relCount && count < 255; i++) {
            int idx = (relHead + i) % RELIABLE_QUEUE;
            int need = 4 + relLen[idx];
            if (need > budget) {
                break;
            }
            if (relSentOnce[idx] && now - relSent[idx] < again) {
                continue;
            }
            put16(out, pos, (relHeadId + i) & 0xFFFF);
            put16(out, pos + 2, relLen[idx] | (relMore[idx] ? MORE : 0));
            System.arraycopy(relData[idx], 0, out, pos + 4, relLen[idx]);
            pos += need;
            budget -= need;
            relSent[idx] = now;
            relSentOnce[idx] = true;
            count++;
        }
        out[countPos] = (byte) count;
        if (len > 0) {
            System.arraycopy(payload, off, out, pos, len);
            pos += len;
        }
        boolean queued = rawSend(pos);
        lastSendNanos = now;
        ackPending = false;
        receivedSinceSend = 0;
        packetsSent++;
        bytesSent += pos;
        return queued;
    }

    // ---------------------------------------------------------------- receiving datagrams

    /** One datagram that arrived for this channel (the server hands in what it received for the client). */
    void handle(UdpPacket p, long now) {
        byte[] d = p.data;
        int n = p.length;
        if (n < HEADER || get16(d, 0) != MAGIC) {
            badPackets++;
            return;
        }
        int kind = d[2] & 255;
        int tok = get32(d, 3);
        if (kind == KIND_CONNECT) {
            if (passive && tok != 0 && (state == CONNECTING || (state == CONNECTED && tok == token))) {
                int app = n >= HEADER + 4 ? get32(d, HEADER) : 0;
                if (app != appId) {
                    int keep = token;
                    token = tok;
                    sendSimple(KIND_CLOSE, REASON_REFUSED, true);
                    token = keep;
                    return;
                }
                token = tok;
                sendSimple(KIND_ACCEPT, 0, false);
                if (state == CONNECTING) {
                    state = CONNECTED;
                    lastRecvNanos = now;
                }
            }
            return;
        }
        if (token == 0 || tok != token) {
            badPackets++;
            return;
        }
        if (kind == KIND_ACCEPT) {
            if (state == CONNECTING) {
                state = CONNECTED;
            }
            lastRecvNanos = now;
            return;
        }
        if (kind == KIND_CLOSE) {
            int reason = n > HEADER ? d[HEADER] & 255 : REASON_NORMAL;
            if (reason == REASON_NORMAL) {
                state = CLOSED;
                if (ownsSocket) {
                    socket.close();
                }
            } else {
                closeReason = reason;
                fail(reasonText(reason));
            }
            return;
        }
        if (kind != KIND_DATA) {
            badPackets++;
            return;
        }
        if (state == CONNECTING) {
            if (passive) {
                return;
            }
            state = CONNECTED; // DATA before ACCEPT (reordering): the peer has accepted the connection
        }
        lastRecvNanos = now;
        packetsReceived++;
        bytesReceived += n;
        receivedSinceSend++;

        int seq = get16(d, 7);
        boolean newest = false;
        if (!haveRemote) {
            haveRemote = true;
            remoteSeq = seq;
            recvBits = 0;
            remoteSeqNanos = p.receivedNanos;
            newest = true;
        } else {
            int ahead = (seq - remoteSeq) & 0xFFFF;
            if (ahead == 0) {
                duplicates++;
                return;
            }
            if (ahead < 32768) {
                if (ahead > 32) {
                    recvBits = 0;
                } else if (ahead == 32) {
                    recvBits = 0x80000000;
                } else {
                    recvBits = (recvBits << ahead) | (1 << (ahead - 1));
                }
                remoteSeq = seq;
                remoteSeqNanos = p.receivedNanos;
                newest = true;
            } else {
                int back = (remoteSeq - seq) & 0xFFFF;
                if (back > 32 || (recvBits & (1 << (back - 1))) != 0) {
                    duplicates++;
                    return;
                }
                recvBits |= 1 << (back - 1);
            }
        }

        // what the other side tells about our datagrams
        int ack = get16(d, 9);
        int ackBits = get32(d, 11);
        int delay = get16(d, 15);
        markAcked(ack, p.receivedNanos, delay, true);
        for (int i = 0; i < 32; i++) {
            if (((ackBits >>> i) & 1) != 0) {
                markAcked((ack - 1 - i) & 0xFFFF, p.receivedNanos, 0, false);
            }
        }
        int relAck = get16(d, 17);
        int done = (relAck - relHeadId) & 0xFFFF;
        if (done >= 1 && done <= relCount) {
            relHead = (relHead + done) % RELIABLE_QUEUE;
            relCount -= done;
            relHeadId = relAck;
        }

        // reliable messages, then the unreliable payload
        int pos = HEADER;
        if (pos >= n) {
            return;
        }
        int count = d[pos++] & 255;
        for (int i = 0; i < count; i++) {
            if (pos + 4 > n) {
                badPackets++;
                return;
            }
            int id = get16(d, pos);
            int lenField = get16(d, pos + 2);
            int len = lenField & 0x7FFF;
            boolean more = (lenField & MORE) != 0;
            pos += 4;
            if (len > MAX_RELIABLE || pos + len > n) {
                badPackets++;
                return;
            }
            if (id == relExpected) {
                if (more || bigLen > 0) {
                    if (acceptPart(d, pos, len, more)) {
                        relExpected = (relExpected + 1) & 0xFFFF;
                    }
                } else if (inCount < INBOX) {
                    int slot = (inHead + inCount) % INBOX;
                    System.arraycopy(d, pos, inData[slot], 0, len);
                    inLen[slot] = len;
                    inRel[slot] = true;
                    inBig[slot] = false;
                    inCount++;
                    relExpected = (relExpected + 1) & 0xFFFF;
                }
            }
            pos += len;
        }
        if (count > 0) {
            ackPending = true; // acknowledge promptly to stop retransmission
        }
        if (newest && pos < n) {
            if (inCount < INBOX) {
                int slot = (inHead + inCount) % INBOX;
                int len = n - pos;
                System.arraycopy(d, pos, inData[slot], 0, len);
                inLen[slot] = len;
                inRel[slot] = false;
                inBig[slot] = false;
                inCount++;
            } else {
                inboxDropped++;
            }
        }
    }

    /**
     * One part of a big message arrived in order. True: it is used up (the id moves on). False: it cannot be taken now
     * (the previous big message was not read yet, or the inbox is full for the last part) and will come again.
     */
    private boolean acceptPart(byte[] d, int pos, int len, boolean more) {
        if (bigLen == 0 && bigReady) {
            return false; // the buffer still holds the previous message
        }
        if (!more && inCount >= INBOX) {
            return false;
        }
        if (bigLen + len > MAX_BIG) {
            bigLen = 0; // invalid sequence: discard the message
            badPackets++;
            return true;
        }
        if (bigData == null) {
            bigData = new byte[MAX_BIG]; // once, for the first big message of this connection
        }
        System.arraycopy(d, pos, bigData, bigLen, len);
        bigLen += len;
        if (!more) {
            int slot = (inHead + inCount) % INBOX;
            inLen[slot] = bigLen;
            inRel[slot] = true;
            inBig[slot] = true;
            inCount++;
            bigLen = 0;
            bigReady = true;
        }
        return true;
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case REASON_REFUSED:
                return "the server refused the connection (is the appId the same on both sides?)";
            case REASON_FULL:
                return "the server is full";
            case REASON_KICKED:
                return "kicked out by the server";
            case REASON_BANNED:
                return "banned from the server";
            case REASON_UNKNOWN:
                return "the server does not know this connection any more (restarted?): connect again";
            default:
                return reason >= REASON_APP ? "refused or closed by the server (code " + reason + ")"
                        : "closed by the server (reason " + reason + ")";
        }
    }

    private void markAcked(int seq, long arrivedNanos, int delayMillis, boolean sample) {
        int slot = seq & (WINDOW - 1);
        if (!sentValid[slot] || sentSeq[slot] != seq || sentAcked[slot]) {
            return;
        }
        sentAcked[slot] = true;
        if (sample) {
            float ms = (arrivedNanos - sentNanos[slot]) / (float) MILLI - delayMillis;
            if (ms < 0f) {
                ms = 0f;
            }
            rtt = haveRtt ? rtt * 0.875f + ms * 0.125f : ms;
            haveRtt = true;
        }
    }
}
