// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Random;

/**
 * Discovers servers of one application and queries their state, for LAN game lists and server lists with player count and
 * ping. Sends a query datagram ({@code KIND_QUERY}) and collects the replies ({@code KIND_INFO}) of {@link NetServer}
 * (see {@link NetServer#setInfo}). Does not block; allocates only when a server appears or changes its name.
 * <pre>
 * ServerBrowser browser = ServerBrowser.create(0x5A01);   // only servers of this appId answer
 * browser.scanLan(7777);                                  // ask everybody on the local network, port 7777
 * // every tick:
 * browser.update();
 * for (int i = 0; i &lt; browser.getCount(); i++) show(browser.getName(i), browser.getPlayers(i), browser.getPingMillis(i));
 * // the player picks one:  engine.connectNet(browser.getAddress(i).getHostAddress(), browser.getPort(i), 0x5A01)
 * </pre>
 * Servers remain listed while they reply; the query should be repeated every 1 to 2 s ({@link #scanLan}, {@link #query}).
 * A server that is silent for {@link #setExpiry} (default 6 s) is removed. Broadcasts do not cross routers and some Wi-Fi
 * networks block them; {@link #query} with a known address is used there. Replies are not authenticated.
 */
public final class ServerBrowser {
    /** The most servers the list holds. */
    public static final int MAX_SERVERS = 64;

    private static final long MILLI = 1000000L;

    private final UdpSocket socket;
    private final int appId;
    private final int nonce;
    private final ArrayList<InetAddress> targets = new ArrayList<InetAddress>();
    private final byte[] out = new byte[NetChannel.HEADER + 4];
    private final UdpPacket rx = new UdpPacket();
    private long expiryNanos = 6000L * MILLI;
    private long lastAskNanos;

    private int count;
    private final InetAddress[] addr = new InetAddress[MAX_SERVERS];
    private final int[] port = new int[MAX_SERVERS];
    private final byte[][] nameBytes = new byte[MAX_SERVERS][];
    private final String[] name = new String[MAX_SERVERS];
    private final int[] players = new int[MAX_SERVERS];
    private final int[] maxPlayers = new int[MAX_SERVERS];
    private final int[] serverApp = new int[MAX_SERVERS];
    private final byte[][] data = new byte[MAX_SERVERS][];
    private final int[] dataLen = new int[MAX_SERVERS];
    private final int[] ping = new int[MAX_SERVERS];
    private final long[] seen = new long[MAX_SERVERS];

    private ServerBrowser(UdpSocket socket, int appId) {
        this.socket = socket;
        this.appId = appId;
        Random r = new Random(System.nanoTime() ^ ((long) System.identityHashCode(this) << 32));
        int t;
        do {
            t = r.nextInt();
        } while (t == 0);
        this.nonce = t;
        out[0] = (byte) (NetChannel.MAGIC >> 8);
        out[1] = (byte) NetChannel.MAGIC;
        out[2] = (byte) NetChannel.KIND_QUERY;
        out[3] = (byte) (t >> 24);
        out[4] = (byte) (t >> 16);
        out[5] = (byte) (t >> 8);
        out[6] = (byte) t;
        out[NetChannel.HEADER] = (byte) (appId >> 24);
        out[NetChannel.HEADER + 1] = (byte) (appId >> 16);
        out[NetChannel.HEADER + 2] = (byte) (appId >> 8);
        out[NetChannel.HEADER + 3] = (byte) appId;
    }

    /**
     * Opens the socket (any local port) in the background and returns at once; never throws and never returns null.
     * Collects the broadcast addresses of the local interfaces.
     *
     * @param appId only servers with this game id are listed; 0 lists every Skarph server
     */
    public static ServerBrowser create(int appId) {
        ServerBrowser b = new ServerBrowser(UdpSocket.listen(null, 0, 64), appId);
        try {
            b.targets.add(InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}));
            b.targets.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})); // a server on this machine
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bc = ia.getBroadcast();
                    if (bc != null && !b.targets.contains(bc)) {
                        b.targets.add(bc); // broadcast address of each local network
                    }
                }
            }
        } catch (Throwable t) {
            // the two addresses above are enough to start with
        }
        return b;
    }

    /** True while the browser can send and listen; false when its socket failed or it was closed. */
    public boolean isOpen() {
        int s = socket.getState();
        return s == UdpSocket.CONNECTING || s == UdpSocket.OPEN;
    }

    /** The error text when the socket could not be opened, else null. */
    public String getError() {
        return socket.getError();
    }

    /** After this many milliseconds without an answer a server is taken off the list (default 6000). */
    public ServerBrowser setExpiry(int millis) {
        expiryNanos = Math.max(100, millis) * MILLI;
        return this;
    }

    /**
     * Asks every machine of the local network that listens on {@code port} (broadcast). Call it again now and then to refresh the
     * list and the ping. Does not allocate. Returns false when the socket is not usable.
     */
    public boolean scanLan(int port) {
        if (!isOpen()) {
            return false;
        }
        lastAskNanos = System.nanoTime();
        boolean any = false;
        for (int i = 0; i < targets.size(); i++) {
            any |= socket.sendTo(targets.get(i), port, out, 0, out.length);
        }
        return any;
    }

    /**
     * Asks one server (a name or an address): the way to a server in a list, or on a network that passes no broadcasts.
     * A name is looked up in the background, so this returns at once.
     */
    public void query(final String host, final int port) {
        if (host == null || port < 1 || port > 65535 || !isOpen()) {
            return;
        }
        lastAskNanos = System.nanoTime();
        final byte[] q = out.clone();
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    socket.sendTo(InetAddress.getByName(host), port, q, 0, q.length);
                } catch (Throwable e) {
                    // unresolvable name: no entry is created
                }
            }
        }, "skarph-browser-dns");
        t.setDaemon(true);
        t.start();
    }

    /** Reads the answers that arrived and drops servers that were silent for too long. Call it once per tick. */
    public void update() {
        long now = System.nanoTime();
        while (socket.receive(rx)) {
            handle(rx);
        }
        for (int i = count - 1; i >= 0; i--) {
            if (now - seen[i] > expiryNanos) {
                removeAt(i);
            }
        }
    }

    private static int get32(byte[] d, int p) {
        return ((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
    }

    private void handle(UdpPacket p) {
        byte[] d = p.data;
        int n = p.length;
        int pos = NetChannel.HEADER;
        if (n < pos + 10 || (((d[0] & 255) << 8) | (d[1] & 255)) != NetChannel.MAGIC || (d[2] & 255) != NetChannel.KIND_INFO
                || get32(d, 3) != nonce) {
            return; // not a reply to this query
        }
        int app = get32(d, pos);
        if (appId != 0 && app != appId) {
            return;
        }
        int pl = ((d[pos + 4] & 255) << 8) | (d[pos + 5] & 255);
        int mx = ((d[pos + 6] & 255) << 8) | (d[pos + 7] & 255);
        pos += 8;
        int nl = d[pos++] & 255;
        if (pos + nl + 1 > n) {
            return;
        }
        int namePos = pos;
        pos += nl;
        int dl = d[pos++] & 255;
        if (pos + dl > n) {
            return;
        }
        int i = -1;
        for (int k = 0; k < count; k++) {
            if (port[k] == p.port && addr[k].equals(p.address)) {
                i = k;
                break;
            }
        }
        if (i < 0) {
            if (count == MAX_SERVERS) {
                return;
            }
            i = count++;
            addr[i] = p.address;
            port[i] = p.port;
            nameBytes[i] = null;
            name[i] = null;
            data[i] = new byte[128];
        }
        if (nameBytes[i] == null || !same(nameBytes[i], d, namePos, nl)) {
            nameBytes[i] = new byte[nl];
            System.arraycopy(d, namePos, nameBytes[i], 0, nl);
            name[i] = new String(d, namePos, nl, Charset.forName("UTF-8"));
        }
        System.arraycopy(d, pos, data[i], 0, dl);
        dataLen[i] = dl;
        players[i] = pl;
        maxPlayers[i] = mx;
        serverApp[i] = app;
        seen[i] = p.receivedNanos;
        ping[i] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (p.receivedNanos - lastAskNanos) / MILLI));
    }

    private static boolean same(byte[] a, byte[] d, int off, int len) {
        if (a.length != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (a[i] != d[off + i]) {
                return false;
            }
        }
        return true;
    }

    private void removeAt(int i) {
        int last = count - 1;
        // keep the order of the list: shift the rest down
        for (int k = i; k < last; k++) {
            addr[k] = addr[k + 1];
            port[k] = port[k + 1];
            nameBytes[k] = nameBytes[k + 1];
            name[k] = name[k + 1];
            players[k] = players[k + 1];
            maxPlayers[k] = maxPlayers[k + 1];
            serverApp[k] = serverApp[k + 1];
            data[k] = data[k + 1];
            dataLen[k] = dataLen[k + 1];
            ping[k] = ping[k + 1];
            seen[k] = seen[k + 1];
        }
        addr[last] = null;
        nameBytes[last] = null;
        name[last] = null;
        data[last] = null;
        count = last;
    }

    /** Forgets all servers found so far. */
    public void clear() {
        while (count > 0) {
            removeAt(count - 1);
        }
    }

    /** How many servers are in the list. */
    public int getCount() {
        return count;
    }

    /** The address of server {@code i} (0 to getCount() - 1; the list keeps its order while servers stay). */
    public InetAddress getAddress(int i) {
        return addr[i];
    }

    /** The port of server {@code i}. */
    public int getPort(int i) {
        return port[i];
    }

    /** The name the server gave itself ({@link NetServer#setInfo}); empty when none. */
    public String getName(int i) {
        return name[i];
    }

    /** How many clients server {@code i} has. */
    public int getPlayers(int i) {
        return players[i];
    }

    /** How many clients server {@code i} takes. */
    public int getMaxPlayers(int i) {
        return maxPlayers[i];
    }

    /** The game id of server {@code i} (the one asked for, or any when the browser was made for 0). */
    public int getServerAppId(int i) {
        return serverApp[i];
    }

    /** Milliseconds between the last question sent and the answer of server {@code i}: approximate ping. */
    public int getPingMillis(int i) {
        return ping[i];
    }

    /** Milliseconds since server {@code i} last answered. */
    public int getAgeMillis(int i) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - seen[i]) / MILLI);
    }

    /** Length of the data block of server {@code i} ({@link NetServer#setInfo}). */
    public int getDataLength(int i) {
        return dataLen[i];
    }

    /** Copies the data block of server {@code i} into {@code outBytes} (at most what fits) and returns the number of bytes copied. */
    public int getData(int i, byte[] outBytes, int offset) {
        int n = Math.min(dataLen[i], outBytes.length - offset);
        if (n <= 0) {
            return 0;
        }
        System.arraycopy(data[i], 0, outBytes, offset, n);
        return n;
    }

    /** Closes the socket. */
    public void close() {
        socket.close();
    }
}
