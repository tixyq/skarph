// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Random;

/**
 * UDP socket for the game loop. Datagrams are sent and received by background threads and exchanged with the game thread
 * through fixed-size queues; {@link #send} and {@link #receive} neither block nor allocate. {@link NetChannel} adds a
 * connection, acknowledgements and reliable messages on top of this class.
 * <ul>
 * <li>{@link #connect} creates a client socket for one peer and returns at once. The host name is resolved and the socket
 *     is opened in the background; the state changes from {@link #CONNECTING} to {@link #OPEN} or {@link #FAILED}.</li>
 * <li>{@link #listen} creates a server socket bound to a port and not connected to a peer. It receives from any sender
 *     ({@link UdpPacket#address}, {@link UdpPacket#port}) and sends with {@link #sendTo}.</li>
 * <li>Each queue holds a fixed number of datagrams; a datagram that does not fit is dropped and counted.</li>
 * <li>A datagram is at most {@link #MAX_PACKET} bytes, which avoids IP fragmentation on a path with a 1500 byte MTU.</li>
 * <li>Delivery is not guaranteed: datagrams may be lost, duplicated or reordered. There is no encryption or authentication.</li>
 * <li>{@link #setSimulation} drops and delays outgoing datagrams.</li>
 * </ul>
 * Getters may be called from any thread; {@link #send}, {@link #sendTo} and {@link #receive} are intended for a single
 * thread. Android requires the INTERNET permission. {@code Engine.connectUdp} creates a client socket that is closed when
 * the program ends.
 */
public final class UdpSocket {
    /** The largest datagram that can be sent or received, in bytes. */
    public static final int MAX_PACKET = 1200;
    /** Default number of datagrams each of the two queues holds. */
    public static final int DEFAULT_QUEUE = 128;

    /** The socket is being opened; datagrams sent now are queued. */
    public static final int CONNECTING = 0;
    /** The socket is open. */
    public static final int OPEN = 1;
    /** Could not start (bad address, server not found, port in use): {@link #getError()} says why. */
    public static final int FAILED = 2;
    /** Closed by {@link #close()}. */
    public static final int CLOSED = 3;

    private static final long MILLI = 1000000L;

    private final String host;
    private final int port;
    private final int localPortWanted;
    private final int capacity;
    private final boolean listening;
    private final String bindHost;

    private volatile int state = CONNECTING;
    private volatile String error;
    private volatile DatagramSocket socket;
    private volatile InetAddress remote;
    private volatile boolean closing;

    // receive queue (ring), filled by the receive thread, emptied by the game thread
    private final Object rxLock = new Object();
    private final byte[][] rxData;
    private final int[] rxLen;
    private final InetAddress[] rxAddr;
    private final int[] rxPort;
    private final long[] rxNanos;
    private int rxHead;
    private int rxCount;

    // send queue (ring), filled by the game thread, emptied by the send thread
    private final Object txLock = new Object();
    private final byte[][] txData;
    private final int[] txLen;
    private final long[] txDue;
    private final InetAddress[] txAddr;
    private final int[] txPort;
    private int txHead;
    private int txCount;
    private int lossPercent;
    private int latencyMs;
    private final Random random = new Random(System.nanoTime());

    private volatile long packetsSent;
    private volatile long packetsReceived;
    private volatile long bytesSent;
    private volatile long bytesReceived;
    private volatile int droppedIn;
    private volatile int droppedOut;
    private volatile int simulatedLost;
    private volatile int unreachable;
    private volatile int ioErrors;

    private UdpSocket(String host, int port, int localPort, int capacity, boolean listening, String bindHost) {
        this.host = host;
        this.port = port;
        this.localPortWanted = localPort;
        this.listening = listening;
        this.bindHost = bindHost;
        this.capacity = Math.max(1, capacity);
        this.rxData = new byte[this.capacity][MAX_PACKET];
        this.rxLen = new int[this.capacity];
        this.rxAddr = new InetAddress[this.capacity];
        this.rxPort = new int[this.capacity];
        this.rxNanos = new long[this.capacity];
        this.txData = new byte[this.capacity][MAX_PACKET];
        this.txLen = new int[this.capacity];
        this.txDue = new long[this.capacity];
        this.txAddr = new InetAddress[this.capacity];
        this.txPort = new int[this.capacity];
    }

    /** {@link #connect(String, int, int, int)} with any local port and {@link #DEFAULT_QUEUE}. */
    public static UdpSocket connect(String host, int port) {
        return connect(host, port, 0, DEFAULT_QUEUE);
    }

    /**
     * Starts talking to {@code host:port} (a name or an IP address) in the background and returns at once. Never throws and
     * never returns null: a bad address gives a socket that is already {@link #FAILED}.
     *
     * @param localPort the local port to use, 0 = any free one (a fixed port is needed only by peers that must be reachable
     *                  at a known port)
     * @param queueSize how many datagrams each queue holds (at least 1; memory is queueSize * 2 * {@link #MAX_PACKET})
     */
    public static UdpSocket connect(String host, int port, int localPort, int queueSize) {
        final UdpSocket s = new UdpSocket(host, port, localPort, queueSize, false, null);
        if (host == null || host.length() == 0) {
            s.fail("no host");
        } else if (port < 1 || port > 65535) {
            s.fail("bad port " + port);
        } else if (localPort < 0 || localPort > 65535) {
            s.fail("bad local port " + localPort);
        } else {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    s.runSender();
                }
            }, "skarph-udp-tx");
            t.setDaemon(true);
            t.start();
        }
        return s;
    }

    /**
     * Creates a server socket bound to {@code port} (0 = any free port, see {@link #getLocalPort()}) that receives datagrams
     * from any sender, and returns at once. {@code bindHost} is the local address to listen on (null or empty =
     * all of them). Never throws and never returns null: a port that cannot be bound results in state {@link #FAILED}
     * once the background thread has tried to open it (see {@link #awaitOpen}).
     * Use {@link #receive} (the sender is in {@link UdpPacket}) and {@link #sendTo}; {@link #send} is refused.
     *
     * @param queueSize how many datagrams each queue holds (at least 1; memory is queueSize * 2 * {@link #MAX_PACKET})
     */
    public static UdpSocket listen(String bindHost, int port, int queueSize) {
        final UdpSocket s = new UdpSocket(bindHost, port, port, queueSize, true, bindHost == null || bindHost.length() == 0 ? null : bindHost);
        if (port < 0 || port > 65535) {
            s.fail("bad port " + port);
        } else {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    s.runSender();
                }
            }, "skarph-udp-tx");
            t.setDaemon(true);
            t.start();
        }
        return s;
    }

    /** True for a socket made by {@link #listen}. */
    public boolean isListening() {
        return listening;
    }

    /**
     * Blocks the calling thread until the socket leaves {@link #CONNECTING}, at most {@code millis}. Not for use on the
     * game thread.
     *
     * @return true when the socket is {@link #OPEN}
     */
    public boolean awaitOpen(int millis) {
        long end = System.nanoTime() + millis * MILLI;
        while (state == CONNECTING && System.nanoTime() < end) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return state == OPEN;
    }

    /** {@link #CONNECTING}, {@link #OPEN}, {@link #FAILED} or {@link #CLOSED}. */
    public int getState() {
        return state;
    }

    /** True while datagrams can be sent and received. */
    public boolean isOpen() {
        return state == OPEN;
    }

    /** What went wrong, or null unless the state is {@link #FAILED}. */
    public String getError() {
        return state == FAILED ? error : null;
    }

    /** The host name or address given to {@link #connect}. */
    public String getHost() {
        return host;
    }

    /** The remote port given to {@link #connect}. */
    public int getPort() {
        return port;
    }

    /** The address of the server once it is found, else null. */
    public InetAddress getRemoteAddress() {
        return remote;
    }

    /** The local port in use, or -1 while the socket is not open. */
    public int getLocalPort() {
        DatagramSocket s = socket;
        return s != null && state == OPEN ? s.getLocalPort() : -1;
    }

    /**
     * Queues a datagram for sending (the bytes are copied, so the array may be reused at once).
     *
     * @return false when it was not queued: the socket is failed or closed, the queue is full (counted in
     *         {@link #getDroppedOut()}) or the length is not 0..{@link #MAX_PACKET}. True does not mean that it arrived.
     */
    public boolean send(byte[] data, int offset, int length) {
        if (listening) {
            return false; // no peer: use sendTo
        }
        return enqueue(null, 0, data, offset, length);
    }

    /**
     * Like {@link #send} for a socket made by {@link #listen}: queues a datagram for {@code address:port} (the bytes are
     * copied). Also the way to send to a broadcast address. Does not allocate.
     *
     * @return false when it was not queued: not a listening socket, no address, a bad port, or the reasons of {@link #send}
     */
    public boolean sendTo(InetAddress address, int port, byte[] data, int offset, int length) {
        if (!listening || address == null || port < 1 || port > 65535) {
            return false;
        }
        return enqueue(address, port, data, offset, length);
    }

    private boolean enqueue(InetAddress address, int port, byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || length > MAX_PACKET || offset > data.length - length) {
            return false;
        }
        synchronized (txLock) {
            int st = state;
            if (closing || (st != CONNECTING && st != OPEN)) {
                return false;
            }
            if (txCount == capacity) {
                droppedOut++;
                return false;
            }
            if (lossPercent > 0 && random.nextInt(100) < lossPercent) {
                simulatedLost++; // reported as sent, as for a datagram lost in transit
                return true;
            }
            int slot = (txHead + txCount) % capacity;
            System.arraycopy(data, offset, txData[slot], 0, length);
            txLen[slot] = length;
            txAddr[slot] = address;
            txPort[slot] = port;
            txDue[slot] = System.nanoTime() + latencyMs * MILLI;
            txCount++;
            txLock.notifyAll();
        }
        return true;
    }

    /**
     * Copies the oldest received datagram into {@code out} and removes it from the queue. Does not allocate.
     *
     * @return false when nothing has arrived
     */
    public boolean receive(UdpPacket out) {
        synchronized (rxLock) {
            if (rxCount == 0) {
                return false;
            }
            int n = rxLen[rxHead];
            System.arraycopy(rxData[rxHead], 0, out.data, 0, n);
            out.length = n;
            out.address = rxAddr[rxHead];
            out.port = rxPort[rxHead];
            out.receivedNanos = rxNanos[rxHead];
            rxAddr[rxHead] = null;
            rxHead = (rxHead + 1) % capacity;
            rxCount--;
            return true;
        }
    }

    /**
     * Simulates an unreliable network for outgoing datagrams: each is dropped with the given probability (0..100 %) and the
     * rest are delayed by {@code latencyMillis} (one way, order preserved, no jitter). Incoming datagrams are not affected.
     * 0 and 0 disable the simulation.
     */
    public void setSimulation(int lossPercent, int latencyMillis) {
        synchronized (txLock) {
            this.lossPercent = Math.max(0, Math.min(100, lossPercent));
            this.latencyMs = Math.max(0, Math.min(10000, latencyMillis));
            txLock.notifyAll();
        }
    }

    /**
     * Closes the socket after the queued datagrams have been sent (without simulated delay). Returns at once. Has no effect
     * if the socket is already closed or failed.
     */
    public void close() {
        synchronized (txLock) {
            if (closing) {
                return;
            }
            closing = true;
            if (state != FAILED) {
                state = CLOSED;
            }
            txLock.notifyAll();
        }
    }

    /** Datagrams handed to the network so far. */
    public long getPacketsSent() {
        return packetsSent;
    }

    /** Datagrams received so far (also those dropped because the queue was full). */
    public long getPacketsReceived() {
        return packetsReceived;
    }

    /** Bytes of the datagrams sent so far (payload only, without the IP and UDP headers). */
    public long getBytesSent() {
        return bytesSent;
    }

    /** Bytes of the datagrams received so far (payload only). */
    public long getBytesReceived() {
        return bytesReceived;
    }

    /** Received datagrams thrown away because the receive queue was full or the datagram was bigger than {@link #MAX_PACKET}. */
    public int getDroppedIn() {
        return droppedIn;
    }

    /** Datagrams refused by {@link #send} because the send queue was full. */
    public int getDroppedOut() {
        return droppedOut;
    }

    /** Datagrams thrown away by {@link #setSimulation}. */
    public int getSimulatedLost() {
        return simulatedLost;
    }

    /** Number of ICMP port-unreachable reports from the operating system (not available on all platforms). */
    public int getUnreachable() {
        return unreachable;
    }

    // ---------------------------------------------------------------- background threads

    private void fail(String message) {
        synchronized (txLock) {
            if (state == FAILED) {
                return;
            }
            error = message;
            state = FAILED;
            closing = true;
            txLock.notifyAll();
        }
    }

    /** The send thread: finds the server, opens the socket, starts the receive thread, sends what is queued. */
    private void runSender() {
        DatagramSocket s = null;
        try {
            InetAddress addr = null;
            try {
                addr = InetAddress.getByName(listening ? bindHost : host); // null = the wildcard / loopback address
            } catch (UnknownHostException e) {
                fail(listening ? "cannot find the local address " + bindHost
                        : "cannot find the server " + host + " (no network, a wrong name, or on Android a missing INTERNET permission)");
                return;
            }
            if (closing) {
                return;
            }
            try {
                if (listening) {
                    s = bindHost == null ? new DatagramSocket(localPortWanted) : new DatagramSocket(localPortWanted, addr);
                    s.setBroadcast(true);
                } else {
                    s = localPortWanted > 0 ? new DatagramSocket(localPortWanted) : new DatagramSocket();
                    s.connect(addr, port);
                }
                s.setSoTimeout(250); // interval at which the receive loop checks "closing"
            } catch (SocketException e) {
                fail("cannot open the UDP socket: " + e.getMessage());
                return;
            }
            socket = s;
            remote = listening ? null : addr;
            synchronized (txLock) {
                if (!closing) {
                    state = OPEN;
                }
            }
            final DatagramSocket rs = s;
            Thread rx = new Thread(new Runnable() {
                public void run() {
                    runReceiver(rs);
                }
            }, "skarph-udp-rx");
            rx.setDaemon(true);
            rx.start();
            sendLoop(s);
        } catch (SecurityException e) {
            fail("the program may not use the network (on Android: add the INTERNET permission to AndroidManifest.xml)");
        } catch (Throwable t) {
            fail("unexpected error: " + t);
        } finally {
            synchronized (txLock) {
                closing = true;
                if (state != FAILED) {
                    state = CLOSED;
                }
            }
            if (s != null) {
                s.close(); // also wakes the receive thread
            }
        }
    }

    private void sendLoop(DatagramSocket s) {
        byte[] buf = new byte[MAX_PACKET];
        DatagramPacket p = new DatagramPacket(buf, MAX_PACKET);
        while (true) {
            int n;
            InetAddress to;
            int toPort;
            synchronized (txLock) {
                while (true) {
                    if (txCount == 0) {
                        if (closing) {
                            return;
                        }
                        try {
                            txLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    long wait = closing ? 0 : txDue[txHead] - System.nanoTime();
                    if (wait <= 0) {
                        break;
                    }
                    try {
                        txLock.wait(wait / MILLI, (int) (wait % MILLI));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                n = txLen[txHead];
                to = txAddr[txHead];
                toPort = txPort[txHead];
                txAddr[txHead] = null;
                System.arraycopy(txData[txHead], 0, buf, 0, n);
                txHead = (txHead + 1) % capacity;
                txCount--;
            }
            p.setLength(n);
            if (to != null) {
                p.setAddress(to);
                p.setPort(toPort);
            }
            try {
                s.send(p);
                packetsSent++;
                bytesSent += n;
            } catch (PortUnreachableException e) {
                unreachable++;
            } catch (IOException e) {
                ioErrors++; // the datagram is lost
            }
        }
    }

    private void runReceiver(DatagramSocket s) {
        byte[] buf = new byte[MAX_PACKET + 1]; // one extra byte detects oversized datagrams
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closing) {
            p.setLength(buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (PortUnreachableException e) {
                unreachable++;
                continue;
            } catch (IOException e) {
                if (closing) {
                    return;
                }
                ioErrors++;
                try {
                    Thread.sleep(20); // avoid a busy loop on a failed socket
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            long now = System.nanoTime();
            int n = p.getLength();
            packetsReceived++;
            bytesReceived += n;
            if (n > MAX_PACKET) {
                droppedIn++;
                continue;
            }
            synchronized (rxLock) {
                if (rxCount == capacity) {
                    droppedIn++;
                } else {
                    int slot = (rxHead + rxCount) % capacity;
                    System.arraycopy(buf, 0, rxData[slot], 0, n);
                    rxLen[slot] = n;
                    rxAddr[slot] = p.getAddress();
                    rxPort[slot] = p.getPort();
                    rxNanos[slot] = now;
                    rxCount++;
                }
            }
        }
    }
}
