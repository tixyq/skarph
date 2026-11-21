// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 1.3: the server. NetServer, NetClient, ServerBrowser and ServerLoop on real sockets on the loopback interface, against
 * real NetChannel clients and against a hand-written peer that speaks the wire format byte by byte: handshake and
 * refusals (appId, full, banned, per address, filter with connect data), kicking, leaving and its events, replaced and
 * forgotten connections, rooms and broadcast, big messages under loss, discovery, the loop with its callbacks, Engine
 * wiring and zero allocation of the server tick.
 */
public class ServerTest {
    static int failed;
    static final byte[] LO = {127, 0, 0, 1};
    static final int APP = 0x5A01;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    interface Cond { boolean ok(); }

    static InetAddress lo() throws Exception { return InetAddress.getByAddress(LO); }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    static String str(NetMessage m) { return new String(m.data, 0, m.length, java.nio.charset.StandardCharsets.UTF_8); }

    /** Runs the server's update() and the clients' update() every 2 ms until the condition holds. */
    static boolean pump(Cond c, int ms, NetServer s, List<NetChannel> clients) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (s != null) s.update();
            for (NetChannel ch : clients) ch.update();
            if (c.ok()) return true;
            try { Thread.sleep(2); } catch (InterruptedException e) { return false; }
        }
        if (s != null) s.update();
        for (NetChannel ch : clients) ch.update();
        return c.ok();
    }

    static boolean pump(Cond c, int ms, NetServer s, NetChannel... clients) {
        return pump(c, ms, s, new ArrayList<NetChannel>(Arrays.asList(clients)));
    }

    static NetServer start(int max) {
        NetServer s = NetServer.listen("127.0.0.1", 0, APP, max);
        if (!s.awaitRunning(3000)) throw new RuntimeException("server did not start: " + s.getError());
        return s;
    }

    static NetChannel client(NetServer s, int appId) {
        return NetChannel.connect("127.0.0.1", s.getLocalPort(), appId);
    }

    /** Reads every pending event into a list: "J<id>" and "L<id>:<reason>". */
    static List<String> events(NetServer s) {
        List<String> r = new ArrayList<String>();
        NetEvent e = new NetEvent();
        while (s.pollEvent(e)) r.add((e.type == NetEvent.JOINED ? "J" : "L") + e.client.getId() + (e.type == NetEvent.LEFT ? ":" + e.reason : ""));
        return r;
    }

    /** Connects clients one after the other, so that client i gets the server's id i + 1. */
    static void connectAll(NetServer s, List<NetChannel> cl, int n) {
        for (int i = 0; i < n; i++) {
            NetChannel c = client(s, APP);
            cl.add(c);
            final int want = cl.size();
            pump(() -> s.getClientCount() == want && c.isConnected(), 4000, s, cl);
        }
    }

    static NetClient waitClient(NetServer s, List<NetChannel> cl, int id) {
        pump(() -> s.getClient(id) != null, 3000, s, cl);
        return s.getClient(id);
    }

    // ---- the wire format by hand (independent of NetChannel)
    static byte[] pkt(int kind, int token, int seq, int ack, int ackBits, int relAck, byte[] body) {
        byte[] d = new byte[19 + body.length];
        d[0] = 'S'; d[1] = 'K'; d[2] = (byte) kind;
        d[3] = (byte) (token >> 24); d[4] = (byte) (token >> 16); d[5] = (byte) (token >> 8); d[6] = (byte) token;
        d[7] = (byte) (seq >> 8); d[8] = (byte) seq; d[9] = (byte) (ack >> 8); d[10] = (byte) ack;
        d[11] = (byte) (ackBits >> 24); d[12] = (byte) (ackBits >> 16); d[13] = (byte) (ackBits >> 8); d[14] = (byte) ackBits;
        d[17] = (byte) (relAck >> 8); d[18] = (byte) relAck;
        System.arraycopy(body, 0, d, 19, body.length);
        return d;
    }

    static byte[] connectBody(int appId, byte[] extra) {
        byte[] r = new byte[4 + extra.length];
        r[0] = (byte) (appId >> 24); r[1] = (byte) (appId >> 16); r[2] = (byte) (appId >> 8); r[3] = (byte) appId;
        System.arraycopy(extra, 0, r, 4, extra.length);
        return r;
    }

    static int u16(byte[] d, int p) { return ((d[p] & 255) << 8) | (d[p + 1] & 255); }
    static int u32(byte[] d, int p) { return (u16(d, p) << 16) | u16(d, p + 2); }

    static void rawSend(DatagramSocket s, int port, byte[] d) throws Exception {
        s.send(new DatagramPacket(d, d.length, lo(), port));
    }

    /** Runs the server while waiting for one datagram on the raw socket. */
    static byte[] rawRecv(DatagramSocket s, NetServer srv, int ms) throws Exception {
        s.setSoTimeout(5);
        byte[] buf = new byte[1500];
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            srv.update();
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            return Arrays.copyOf(buf, p.getLength());
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        startupTests();
        acceptTests();
        refusalTests();
        controlTests();
        leaveTests();
        rawWireTests();
        stateRecoveryTests();
        broadcastTests();
        bigMessageTests();
        discoveryTests();
        loopTests();
        exampleTest();
        engineTests();
        allocationTest();
        if (failed > 0) {
            System.out.println(failed + " SERVER TEST(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL SERVER TESTS PASSED");
        System.exit(0);
    }

    // ------------------------------------------------------------------------------------------------

    static void startupTests() throws Exception {
        NetServer s = NetServer.listen("127.0.0.1", 0, APP, 4);
        check(s.awaitRunning(3000) && s.getState() == NetServer.RUNNING && s.isRunning(), "server: opens, state RUNNING");
        check(s.getLocalPort() > 0 && s.getMaxClients() == 4 && s.getAppId() == APP && s.getClientCount() == 0, "server: port, limits, no clients");
        check(s.getSocket().isListening() && !s.getSocket().send(new byte[1], 0, 1), "server: a listening socket refuses send(), only sendTo()");
        NetServer dup = NetServer.listen("127.0.0.1", s.getLocalPort(), APP, 4);
        check(!dup.awaitRunning(3000) && dup.getState() == NetServer.FAILED && dup.getError() != null, "server: a taken port -> FAILED with a reason (" + dup.getError() + ")");
        dup.update();
        check(!dup.pollEvent(new NetEvent()), "server: a failed server updates without harm");
        NetServer bad = NetServer.listen("127.0.0.1", 70000, APP, 4);
        check(bad.getState() == NetServer.FAILED && bad.getError().contains("port"), "server: a bad port fails at once");
        NetServer clamp = NetServer.listen("127.0.0.1", 0, APP, 0);
        check(clamp.getMaxClients() == 1, "server: maxClients is at least 1");
        clamp.close();
        s.close();
        check(s.getState() == NetServer.CLOSED, "server: close() -> CLOSED");
        s.update();
        s.close();
        UdpSocket u = UdpSocket.listen(null, 0, 8);
        check(u.awaitOpen(3000) && u.sendTo(lo(), 9, new byte[3], 0, 3) && !u.sendTo(null, 9, new byte[3], 0, 3) && !u.sendTo(lo(), 0, new byte[3], 0, 3),
                "UdpSocket.listen: sendTo needs an address and a port");
        UdpSocket c = UdpSocket.connect("127.0.0.1", 9);
        check(!c.sendTo(lo(), 9, new byte[1], 0, 1), "UdpSocket: sendTo is for listening sockets only");
        c.close();
        u.close();
    }

    static void acceptTests() throws Exception {
        NetServer s = start(8);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        for (int i = 0; i < 5; i++) cl.add(client(s, APP)); // all at once: the order of the ids is not known
        check(pump(() -> s.getClientCount() == 5 && cl.stream().allMatch(NetChannel::isConnected), 5000, s, cl), "accept: five clients connect to one port");
        List<String> ev = events(s);
        check(ev.equals(Arrays.asList("J1", "J2", "J3", "J4", "J5")), "accept: JOINED events in order, ids 1..5 (" + ev + ")");
        NetClient c3 = s.getClient(3);
        check(c3 != null && c3.getId() == 3 && c3.isPresent() && c3.getAddress().equals(lo()) && c3.getPort() > 0 && s.getAcceptedCount() == 5, "accept: NetClient knows id, address, port");
        check(s.getClient(99) == null && s.getClient(0) == null && s.getClientAt(100) == null && s.getClientAt(-1) == null, "accept: unknown ids and slots give null");
        int seen = 0;
        for (int i = 0; i < s.getClientSlots(); i++) if (s.getClientAt(i) != null) seen++;
        check(seen == 5, "accept: getClientAt walks the five clients");
        c3.setUserData("alice");
        check("alice".equals(s.getClient(3).getUserData()), "accept: user data stays with the client");

        // messages both ways, from each client to the server and back
        NetMessage m = new NetMessage();
        for (int i = 0; i < 5; i++) cl.get(i).sendReliable(b("hello from " + i), 0, ("hello from " + i).length());
        int[] got = new int[1];
        boolean[] from = new boolean[5];
        pump(() -> {
            for (int i = 0; i < s.getClientSlots(); i++) {
                NetClient c = s.getClientAt(i);
                while (c != null && c.receive(m)) {
                    String t = str(m);
                    if (m.reliable && t.startsWith("hello from ")) {
                        int k = t.charAt(11) - '0';
                        if (cl.get(k).getState() == NetChannel.CONNECTED && !from[k]) { from[k] = true; got[0]++; }
                    }
                }
            }
            return got[0] == 5;
        }, 4000, s, cl);
        check(got[0] == 5, "accept: a reliable message from every client reaches the server");
        for (int i = 0; i < s.getClientSlots(); i++) { NetClient c = s.getClientAt(i); if (c != null) c.sendReliable(b("hi " + c.getId()), 0, ("hi " + c.getId()).length()); }
        boolean[] back = new boolean[5];
        pump(() -> {
            for (int i = 0; i < 5; i++) while (cl.get(i).receive(m)) { String t = str(m); if (t.startsWith("hi ")) back[i] = true; }
            return back[0] && back[1] && back[2] && back[3] && back[4];
        }, 4000, s, cl);
        check(back[0] && back[1] && back[2] && back[3] && back[4], "accept: the server's reliable message reaches each client");
        pump(() -> false, 400, s, cl); // some traffic to measure
        check(c3.getRttMillis() >= 0 && c3.getRttMillis() < 200 && c3.getPacketLoss() < 0.5f && c3.getChannel().getRttMillis() == c3.getRttMillis(), "accept: round trip time is measured on the server side (" + c3.getRttMillis() + " ms)");
        // unreliable, sequenced
        cl.get(0).send(b("pos 1"), 0, 5);
        boolean[] pos = new boolean[1];
        pump(() -> {
            for (int i = 0; i < s.getClientSlots(); i++) { NetClient c = s.getClientAt(i); while (c != null && c.receive(m)) if (!m.reliable && str(m).equals("pos 1")) pos[0] = true; }
            return pos[0];
        }, 2000, s, cl);
        check(pos[0], "accept: an unreliable message from a client arrives");
        // a repeated CONNECT of an existing client is answered, not duplicated
        check(s.getClientCount() == 5 && events(s).isEmpty(), "accept: no duplicates, no stray events");
        for (NetChannel c : cl) c.close();
        pump(() -> s.getClientCount() == 0, 3000, s, cl);
        s.close();
    }

    static void refusalTests() throws Exception {
        NetServer s = start(2);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        NetChannel wrong = client(s, APP + 1);
        cl.add(wrong);
        check(pump(() -> wrong.getState() == NetChannel.FAILED, 3000, s, cl) && wrong.getError().contains("refused") && wrong.getCloseReason() == NetChannel.REASON_REFUSED,
                "refuse: another appId -> FAILED 'refused', reason 1");
        check(s.getClientCount() == 0 && s.getRefusedCount() == 1, "refuse: nobody was accepted, the refusal is counted");

        NetChannel a = client(s, APP), b2 = client(s, APP);
        cl.add(a); cl.add(b2);
        pump(() -> s.getClientCount() == 2, 3000, s, cl);
        NetChannel full = client(s, APP);
        cl.add(full);
        check(pump(() -> full.getState() == NetChannel.FAILED, 3000, s, cl) && full.getError().contains("full") && full.getCloseReason() == NetChannel.REASON_FULL && s.getClientCount() == 2,
                "refuse: a full server -> FAILED 'full', reason 2");
        s.close();

        // per address
        NetServer s2 = start(10);
        s2.setMaxClientsPerAddress(2);
        List<NetChannel> c2 = new ArrayList<NetChannel>();
        for (int i = 0; i < 3; i++) c2.add(client(s2, APP));
        pump(() -> s2.getClientCount() == 2 && c2.stream().filter(c -> c.getState() == NetChannel.FAILED).count() == 1, 4000, s2, c2);
        check(s2.getClientCount() == 2 && c2.stream().filter(c -> c.getState() == NetChannel.FAILED).count() == 1, "refuse: at most 2 clients per address");
        s2.close();

        // filter with connect data
        NetServer s3 = start(10);
        final byte[][] seenData = new byte[1][];
        final int[] calls = new int[1];
        s3.setConnectFilter((address, port, data, off, len) -> {
            calls[0]++;
            seenData[0] = Arrays.copyOfRange(data, off, off + len);
            String pw = new String(seenData[0], java.nio.charset.StandardCharsets.UTF_8);
            return pw.equals("open sesame") ? 0 : (pw.equals("crash") ? 1 / (calls[0] - calls[0]) : NetChannel.REASON_APP + 4);
        });
        List<NetChannel> c3 = new ArrayList<NetChannel>();
        NetChannel good = client(s3, APP);
        good.setConnectData(b("open sesame"), 0, 11);
        NetChannel bad = client(s3, APP);
        bad.setConnectData(b("nope"), 0, 4);
        NetChannel boom = client(s3, APP);
        boom.setConnectData(b("crash"), 0, 5);
        c3.add(good); c3.add(bad); c3.add(boom);
        pump(() -> good.isConnected() && bad.getState() == NetChannel.FAILED && boom.getState() == NetChannel.FAILED, 4000, s3, c3);
        check(good.isConnected() && s3.getClientCount() == 1, "filter: the right password gets in");
        check(bad.getState() == NetChannel.FAILED && bad.getCloseReason() == NetChannel.REASON_APP + 4 && bad.getError().contains("code 20"), "filter: its own reason reaches the client (code " + bad.getCloseReason() + ")");
        check(boom.getState() == NetChannel.FAILED && boom.getCloseReason() == NetChannel.REASON_REFUSED, "filter: a filter that throws lets nobody in");
        NetClient in = s3.getClientAt(0);
        byte[] cd = new byte[32];
        int n = in.getConnectData(cd, 0);
        check(in.getConnectDataLength() == 11 && n == 11 && new String(cd, 0, n).equals("open sesame"), "filter: the server keeps the connect data of the client");
        check(!good.setConnectData(new byte[NetChannel.MAX_CONNECT_DATA + 1], 0, NetChannel.MAX_CONNECT_DATA + 1) && !good.setConnectData(null, 0, 0) && good.setConnectData(new byte[3], 0, 0),
                "filter: connect data is limited to MAX_CONNECT_DATA");
        s3.setConnectFilter(null);
        NetChannel any = client(s3, APP);
        c3.add(any);
        check(pump(() -> any.isConnected(), 3000, s3, c3), "filter: removed -> everybody is accepted again");
        s3.close();
    }

    static void controlTests() throws Exception {
        NetServer s = start(8);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        connectAll(s, cl, 3);
        NetChannel a = cl.get(0), b2 = cl.get(1), c = cl.get(2);
        events(s);
        NetClient ca = s.getClient(1);
        s.kick(ca);
        check(pump(() -> a.getState() == NetChannel.FAILED, 3000, s, cl) && a.getError().contains("kicked") && a.getCloseReason() == NetChannel.REASON_KICKED, "kick: the client is told, FAILED 'kicked'");
        check(s.getClientCount() == 2 && !ca.isPresent() && events(s).equals(Arrays.asList("L1:" + NetServer.LEAVE_KICKED)), "kick: LEFT with LEAVE_KICKED, the NetClient is no longer present");
        s.kick(ca);
        s.kick(null);
        check(s.getClientCount() == 2 && events(s).isEmpty(), "kick: kicking a gone client or null does nothing");
        NetClient cb = s.getClient(2);
        s.kick(cb, NetChannel.REASON_APP + 1);
        check(pump(() -> b2.getState() == NetChannel.FAILED, 3000, s, cl) && b2.getCloseReason() == NetChannel.REASON_APP + 1, "kick: a reason of the game's own");
        s.kick(s.getClient(3), 0);
        check(pump(() -> c.getState() == NetChannel.FAILED, 3000, s, cl) && c.getCloseReason() == NetChannel.REASON_KICKED, "kick: reason 0 means the default one, not a silent close");
        events(s);

        // ban
        NetChannel d = client(s, APP), e = client(s, APP);
        cl.add(d); cl.add(e);
        pump(() -> s.getClientCount() == 2, 3000, s, cl);
        events(s);
        s.ban(lo());
        check(s.isBanned(lo()) && pump(() -> d.getState() == NetChannel.FAILED && e.getState() == NetChannel.FAILED, 3000, s, cl) && d.getCloseReason() == NetChannel.REASON_BANNED && s.getClientCount() == 0,
                "ban: the clients of the address are kicked as banned");
        events(s);
        NetChannel f = client(s, APP);
        cl.add(f);
        check(pump(() -> f.getState() == NetChannel.FAILED, 3000, s, cl) && f.getError().contains("banned") && f.getCloseReason() == NetChannel.REASON_BANNED, "ban: a banned address is refused");
        check(s.unban(lo()) && !s.isBanned(lo()) && !s.unban(lo()), "ban: unban");
        NetChannel g = client(s, APP);
        cl.add(g);
        check(pump(() -> g.isConnected(), 3000, s, cl), "ban: after unban the address may connect");
        s.ban(null);
        events(s);
        s.close();
        check(pump(() -> g.getState() == NetChannel.CLOSED, 3000, null, cl) && g.getCloseReason() == -1, "server close: the client sees a normal CLOSED");
        List<String> ev = events(s);
        check(ev.size() == 1 && ev.get(0).endsWith(":" + NetServer.LEAVE_SHUTDOWN), "server close: LEFT with LEAVE_SHUTDOWN for the clients that were there (" + ev + ")");
    }

    static void leaveTests() throws Exception {
        NetServer s = start(8);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        NetChannel a = client(s, APP);
        cl.add(a);
        pump(() -> s.getClientCount() == 1 && a.isConnected(), 3000, s, cl);
        events(s);
        NetClient ca = s.getClient(1);
        // the client says something and leaves at once: the server must still get the message
        a.sendReliable(b("last words"), 0, 10);
        a.update();
        a.close();
        List<NetChannel> none = new ArrayList<NetChannel>();
        pump(() -> s.getClientCount() == 0, 3000, s, none);
        List<String> ev = events(s);
        check(ev.equals(Arrays.asList("L1:" + NetServer.LEAVE_CLOSED)), "leave: the client closes -> LEFT with LEAVE_CLOSED (" + ev + ")");
        NetMessage m = new NetMessage();
        check(ca.receive(m) && str(m).equals("last words") && !ca.receive(m), "leave: messages sent just before leaving are still readable after LEFT");

        // silence -> timeout
        s.setIdleTimeout(400);
        NetChannel q = client(s, APP);
        List<NetChannel> cq = new ArrayList<NetChannel>();
        cq.add(q);
        pump(() -> s.getClientCount() == 1 && q.isConnected(), 3000, s, cq);
        events(s);
        pump(() -> s.getClientCount() == 0, 3000, s, none); // the client is not updated any more: it falls silent
        ev = events(s);
        check(ev.size() == 1 && ev.get(0).equals("L2:" + NetServer.LEAVE_TIMEOUT), "leave: a silent client times out -> LEAVE_TIMEOUT (" + ev + ")");
        // the client comes back to life: the server does not know it and says so at once
        check(pump(() -> q.getState() == NetChannel.FAILED, 3000, s, cq) && q.getCloseReason() == NetChannel.REASON_UNKNOWN && q.getError().contains("connect again"),
                "leave: a dropped client that speaks again is told to reconnect (" + q.getError() + ")");
        s.close();
    }

    static void rawWireTests() throws Exception {
        NetServer s = start(8);
        int port = s.getLocalPort();
        DatagramSocket raw = new DatagramSocket(0, lo());
        int tok = 0x11223344;
        rawSend(raw, port, pkt(1, tok, 0, 0, 0, 0, connectBody(APP, b("data"))));
        byte[] r = rawRecv(raw, s, 2000);
        check(r != null && r.length == 19 && r[0] == 'S' && r[1] == 'K' && r[2] == 2 && u32(r, 3) == tok, "wire: CONNECT -> ACCEPT, 19 bytes, the same token");
        List<String> ev = events(s);
        check(ev.equals(Arrays.asList("J1")) && s.getClient(1).getConnectDataLength() == 4, "wire: the server accepted one client with 4 bytes of connect data");
        rawSend(raw, port, pkt(1, tok, 0, 0, 0, 0, connectBody(APP, new byte[0])));
        r = rawRecv(raw, s, 2000);
        check(r != null && r[2] == 2 && u32(r, 3) == tok && s.getClientCount() == 1 && events(s).isEmpty(), "wire: a repeated CONNECT is answered with ACCEPT again, no second client");
        // DATA with an unreliable message and a reliable one; the server acks both
        byte[] body = cat(new byte[]{1, 0, 0, 0, 3}, b("rel"), b("unrel"));
        rawSend(raw, port, pkt(3, tok, 1, 0, 0, 0, body));
        NetMessage m = new NetMessage();
        NetClient c = s.getClient(1);
        boolean[] ok = new boolean[2];
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end && !(ok[0] && ok[1])) {
            s.update();
            while (c.receive(m)) { if (m.reliable && str(m).equals("rel")) ok[0] = true; if (!m.reliable && str(m).equals("unrel")) ok[1] = true; }
            Thread.sleep(2);
        }
        check(ok[0] && ok[1], "wire: DATA with a reliable and an unreliable message is read by the server");
        byte[] ack = null;
        raw.setSoTimeout(5);
        end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            byte[] x = rawRecv(raw, s, 50);
            if (x != null && x[2] == 3 && u16(x, 9) == 1 && u16(x, 17) == 1) { ack = x; break; }
        }
        check(ack != null, "wire: the server's DATA acknowledges seq 1 (ack field) and reliable id 0 (relAck 1)");

        // a new token from the same address and port replaces the old connection
        int tok2 = 0x55667788;
        rawSend(raw, port, pkt(1, tok2, 0, 0, 0, 0, connectBody(APP, new byte[0])));
        r = rawRecv(raw, s, 2000);
        ev = events(s);
        check(r != null && r[2] == 2 && u32(r, 3) == tok2 && ev.equals(Arrays.asList("L1:" + NetServer.LEAVE_REPLACED, "J2")) && s.getClientCount() == 1,
                "wire: the same address with a new token replaces the old client: LEFT(REPLACED), JOINED (" + ev + ")");

        // wrong appId, tokens, strays
        DatagramSocket raw2 = new DatagramSocket(0, lo());
        rawSend(raw2, port, pkt(1, 7, 0, 0, 0, 0, connectBody(APP + 9, new byte[0])));
        r = rawRecv(raw2, s, 2000);
        check(r != null && r[2] == 4 && u32(r, 3) == 7 && r.length == 20 && r[19] == 1, "wire: another appId -> CLOSE with reason 1 and the client's token");
        rawSend(raw2, port, pkt(1, 0, 0, 0, 0, 0, connectBody(APP, new byte[0])));
        check(rawRecv(raw2, s, 300) == null && s.getClientCount() == 1, "wire: a CONNECT with token 0 is ignored");
        rawSend(raw2, port, pkt(3, 99, 1, 0, 0, 0, new byte[]{0}));
        r = rawRecv(raw2, s, 2000);
        check(r != null && r[2] == 4 && u32(r, 3) == 99 && r[19] == 5 && s.getStrayPackets() == 1, "wire: DATA from an unknown address -> CLOSE reason 5 (unknown connection)");
        rawSend(raw2, port, pkt(4, 99, 0, 0, 0, 0, new byte[]{0}));
        check(rawRecv(raw2, s, 300) == null, "wire: a CLOSE from a stranger is not answered");
        rawSend(raw2, port, new byte[]{1, 2, 3});
        rawSend(raw2, port, cat(new byte[]{'X', 'Y'}, new byte[30]));
        rawSend(raw2, port, pkt(9, 5, 0, 0, 0, 0, new byte[0]));
        rawSend(raw2, port, pkt(6, 5, 0, 0, 0, 0, new byte[0])); // an INFO sent to a server
        rawRecv(raw2, s, 200);
        check(s.getBadPackets() >= 4, "wire: short, wrong magic, unknown kind: thrown away and counted (" + s.getBadPackets() + ")");
        // a client sends a CLOSE: it leaves
        rawSend(raw, port, pkt(4, tok2, 0, 0, 0, 0, new byte[]{0}));
        for (long until = System.currentTimeMillis() + 2000; s.getClientCount() > 0 && System.currentTimeMillis() < until; ) { s.update(); Thread.sleep(2); }
        ev = events(s);
        check(s.getClientCount() == 0 && ev.equals(Arrays.asList("L2:" + NetServer.LEAVE_CLOSED)), "wire: CLOSE from a client -> LEFT(CLOSED) (" + ev + ", " + s.getClientCount() + ")");

        // a CONNECT and then silence: dropped after the handshake time (3 s)
        DatagramSocket mute = new DatagramSocket(0, lo());
        rawSend(mute, port, pkt(1, 0x2468, 0, 0, 0, 0, connectBody(APP, new byte[0])));
        rawRecv(mute, s, 500);
        check(s.getClientCount() == 1, "handshake: accepted");
        long t0 = System.currentTimeMillis();
        while (s.getClientCount() > 0 && System.currentTimeMillis() - t0 < 6000) { s.update(); Thread.sleep(5); }
        long took = System.currentTimeMillis() - t0;
        check(s.getClientCount() == 0 && took > 2000 && took < 5500, "handshake: a client that never sends DATA is dropped after about 3 s (" + took + " ms)");
        events(s);

        // replies to strangers are rationed
        DatagramSocket flood = new DatagramSocket(0, lo());
        for (int i = 0; i < 1000; i++) rawSend(flood, port, pkt(3, 1234, 1, 0, 0, 0, new byte[]{0}));
        int replies = 0;
        flood.setSoTimeout(5);
        long end2 = System.currentTimeMillis() + 800;
        byte[] buf = new byte[1500];
        while (System.currentTimeMillis() < end2) {
            s.update();
            try { flood.receive(new DatagramPacket(buf, buf.length)); replies++; } catch (SocketTimeoutException e) { }
        }
        check(replies > 0 && replies < 450, "defence: 1000 datagrams from a stranger get a rationed number of answers (" + replies + ")");
        raw.close(); raw2.close(); mute.close(); flood.close();
        s.close();
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    static void stateRecoveryTests() throws Exception {
        // a server that is restarted on the same port: the old client gets told at once
        NetServer s = start(4);
        int port = s.getLocalPort();
        NetChannel c = client(s, APP);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        cl.add(c);
        pump(() -> c.isConnected(), 3000, s, cl);
        c.getSocket().setSimulation(0, 0);
        // stop the server abruptly without telling: close the socket only
        s.getSocket().close();
        NetServer s2 = null;
        for (int i = 0; i < 100 && (s2 == null || !s2.isRunning()); i++) {
            Thread.sleep(20);
            s2 = NetServer.listen("127.0.0.1", port, APP, 4);
            s2.awaitRunning(1000);
        }
        check(s2 != null && s2.isRunning(), "restart: a new server takes the port");
        final NetServer s2f = s2;
        check(pump(() -> c.getState() == NetChannel.FAILED, 4000, s2f, cl) && c.getCloseReason() == NetChannel.REASON_UNKNOWN,
                "restart: the client of the old server learns at once that the new one does not know it (" + c.getError() + ")");
        NetChannel again = client(s2, APP);
        cl.add(again);
        check(pump(() -> again.isConnected(), 3000, s2f, cl), "restart: connecting again works");
        s2.close();
        s.close();
    }

    static void broadcastTests() throws Exception {
        NetServer s = start(8);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        connectAll(s, cl, 4);
        events(s);
        s.getClient(1).setRoom(7);
        s.getClient(2).setRoom(7);
        s.getClient(3).setRoom(8);
        check(s.getRoomCount(7) == 2 && s.getRoomCount(8) == 1 && s.getRoomCount(0) == 1 && s.getRoomCount(NetServer.ALL) == 4 && s.getClient(1).getRoom() == 7, "rooms: counted");
        pump(() -> false, 300, s, cl);
        NetMessage m = new NetMessage();
        int[] gotU = new int[4], gotR = new int[4];
        s.broadcast(7, 0, b("room7"), 0, 5);
        s.broadcastReliable(NetServer.ALL, 2, b("all but 2"), 0, 9);
        check(s.broadcastReliable(8, 0, b("only 8"), 0, 6) == 1 && s.broadcast(99, 0, b("x"), 0, 1) == 0, "broadcast: returns how many clients got it");
        pump(() -> {
            for (int i = 0; i < 4; i++) while (cl.get(i).receive(m)) {
                String t = str(m);
                if (t.equals("room7")) gotU[i]++;
                if (t.equals("all but 2")) gotR[i]++;
                if (t.equals("only 8")) gotR[i] += 10;
            }
            return false;
        }, 700, s, cl);
        check(gotU[0] == 1 && gotU[1] == 1 && gotU[2] == 0 && gotU[3] == 0, "broadcast: an unreliable message reaches the room only (" + Arrays.toString(gotU) + ")");
        check(gotR[0] == 1 && gotR[1] == 0 && gotR[2] == 11 && gotR[3] == 1, "broadcast: reliable to all except one, and to one room (" + Arrays.toString(gotR) + ")");
        // relay: what client 0 says is passed on to the others, not back
        int[] relay = new int[4];
        cl.get(0).sendReliable(b("chat"), 0, 4);
        pump(() -> {
            NetClient c = s.getClient(1);
            while (c.receive(m)) if (str(m).equals("chat")) s.broadcastReliable(NetServer.ALL, c.getId(), m.data, 0, m.length);
            for (int i = 0; i < 4; i++) while (cl.get(i).receive(m)) if (str(m).equals("chat")) relay[i]++;
            return false;
        }, 700, s, cl);
        check(relay[0] == 0 && relay[1] == 1 && relay[2] == 1 && relay[3] == 1, "relay: a message is passed on to everybody but its sender (" + Arrays.toString(relay) + ")");
        // many reliable messages to everybody under loss, in order
        s.getSocket().setSimulation(20, 10);
        for (NetChannel c : cl) c.getSocket().setSimulation(20, 10);
        int total = 40;
        int[] next = new int[4];
        boolean[] ordered = {true, true, true, true};
        byte[] one = new byte[4];
        int[] sent = {0};
        pump(() -> {
            if (sent[0] < total) {
                one[0] = (byte) sent[0];
                if (s.broadcastReliable(NetServer.ALL, 0, one, 0, 1) == 4) sent[0]++;
            }
            for (int i = 0; i < 4; i++) while (cl.get(i).receive(m)) { if (m.reliable && m.length == 1) { if ((m.data[0] & 255) != next[i]) ordered[i] = false; next[i]++; } }
            return next[0] == total && next[1] == total && next[2] == total && next[3] == total;
        }, 15000, s, cl);
        check(next[0] == total && next[1] == total && next[2] == total && next[3] == total && ordered[0] && ordered[1] && ordered[2] && ordered[3],
                "broadcast: 40 reliable messages to 4 clients under 20 % loss, each in order exactly once (" + Arrays.toString(next) + ")");
        s.close();
    }

    static void bigMessageTests() throws Exception {
        NetServer s = start(4);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        NetChannel c = client(s, APP);
        cl.add(c);
        pump(() -> s.getClientCount() == 1, 3000, s, cl);
        events(s);
        NetClient sc = s.getClient(1);
        byte[] big = new byte[20000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + i / 256);
        NetMessage small = new NetMessage();
        NetMessage holder = new NetMessage(NetChannel.MAX_BIG);
        check(holder.data.length == NetChannel.MAX_BIG && small.data.length == NetChannel.MAX_PAYLOAD && new NetMessage(5).data.length == NetChannel.MAX_PAYLOAD, "big: NetMessage(capacity) and its minimum");
        check(NetChannel.MAX_BIG == 32768 && !c.sendReliableBig(new byte[NetChannel.MAX_BIG + 1], 0, NetChannel.MAX_BIG + 1) && !c.sendReliableBig(big, 0, 0) && !c.sendReliableBig(null, 0, 600)
                && !c.sendReliableBig(big, 19000, 2000), "big: too long, empty, null and a bad range are refused");
        // client -> server, with loss, in the middle of small messages
        s.getSocket().setSimulation(15, 5);
        c.getSocket().setSimulation(15, 5);
        check(c.sendReliable(b("before"), 0, 6) && c.sendReliableBig(big, 0, big.length) && c.sendReliable(b("after"), 0, 5), "big: queued between two small messages");
        check(c.getReliableFree() == NetChannel.RELIABLE_QUEUE - 2 - 40 && !c.sendReliableBig(new byte[NetChannel.MAX_BIG], 0, NetChannel.MAX_BIG), "big: it takes places of the queue, and more than is free is refused whole");
        List<String> order = new ArrayList<String>();
        byte[][] rec = new byte[1][];
        boolean done = pump(() -> {
            while (sc.receive(holder)) {
                if (holder.length == 6) order.add("before"); else if (holder.length == 5) order.add("after");
                else { order.add("big"); rec[0] = Arrays.copyOf(holder.data, holder.length); }
            }
            return order.size() == 3;
        }, 20000, s, cl);
        check(done && order.equals(Arrays.asList("before", "big", "after")), "big: 20000 bytes under 15 % loss arrive as ONE message, in order with the small ones (" + order + ")");
        check(rec[0] != null && Arrays.equals(rec[0], big), "big: the content is exact");
        // a second big message right after the first, and one in the other direction
        s.getSocket().setSimulation(0, 0);
        c.getSocket().setSimulation(0, 0);
        byte[] big2 = new byte[3000];
        for (int i = 0; i < big2.length; i++) big2[i] = (byte) (255 - i);
        check(sc.sendReliableBig(big, 0, 20000) && pump(() -> sc.getChannel().getReliablePending() < 8, 8000, s, cl), "big: server -> client queued");
        pump(() -> false, 50, s, cl);
        boolean[] two = new boolean[2];
        // two big messages in a row: the second waits until the first is read
        sc.sendReliableBig(big2, 0, big2.length);
        byte[][] got2 = new byte[2][];
        int[] k = {0};
        pump(() -> {
            // read slowly: only when the test decides, to see that the second waits
            while (c.receive(holder)) {
                if (holder.length == 20000) { got2[0] = Arrays.copyOf(holder.data, holder.length); two[0] = true; }
                else if (holder.length == 3000) { got2[1] = Arrays.copyOf(holder.data, holder.length); two[1] = true; }
            }
            return two[0] && two[1];
        }, 10000, s, cl);
        check(two[0] && two[1] && Arrays.equals(got2[0], big) && Arrays.equals(got2[1], big2), "big: server -> client, two big messages one after the other, both exact");
        // a holder that is too small: the message is lost and counted, the next one is read
        sc.sendReliableBig(big2, 0, big2.length);
        sc.sendReliable(b("tail"), 0, 4);
        boolean[] tail = new boolean[1];
        pump(() -> { while (c.receive(small)) if (small.reliable && small.length == 4) tail[0] = true; return tail[0]; }, 8000, s, cl);
        check(tail[0] && c.getBigDropped() == 1, "big: a holder that is too small loses the big message (counted) and the message after it still comes (" + c.getBigDropped() + ")");
        // a message of exactly the part size is not a big one
        check(c.sendReliableBig(big, 0, NetChannel.MAX_RELIABLE) && c.sendReliableBig(big, 0, 513) && c.sendReliableBig(big, 0, 1024), "big: sizes around the part size are accepted");
        int[] lens = new int[3];
        int[] li = {0};
        pump(() -> { NetMessage h = holder; while (sc.receive(h)) { if (li[0] < 3) lens[li[0]++] = h.length; } return li[0] == 3; }, 4000, s, cl);
        check(Arrays.equals(lens, new int[]{512, 513, 1024}), "big: 512, 513 and 1024 bytes arrive as single messages of those sizes (" + Arrays.toString(lens) + ")");
        s.close();
    }

    /** Runs the server and the clients, updating the browsers, until the condition holds (browser answers come from other threads). */
    static boolean browse(Cond c, int ms, NetServer s, List<NetChannel> cl, ServerBrowser... brs) {
        return pump(() -> { for (ServerBrowser b : brs) b.update(); return c.ok(); }, ms, s, cl);
    }

    static void discoveryTests() throws Exception {
        NetServer s = start(6);
        s.setInfo("Arena éè of Tixyq", new byte[]{9, 8, 7}, 0, 3);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        cl.add(client(s, APP));
        pump(() -> s.getClientCount() == 1, 3000, s, cl);
        ServerBrowser br = ServerBrowser.create(APP);
        check(br.isOpen() && br.getCount() == 0, "browser: opens empty");
        boolean found = false;
        for (int i = 0; i < 40 && !found; i++) {
            br.scanLan(s.getLocalPort());
            pump(() -> br.getCount() > 0 && true, 100, s, cl);
            br.update();
            found = br.getCount() > 0;
        }
        check(found && br.getCount() == 1, "browser: a LAN scan finds the server (also on this machine)");
        check(br.getName(0).equals("Arena éè of Tixyq") && br.getPlayers(0) == 1 && br.getMaxPlayers(0) == 6 && br.getPort(0) == s.getLocalPort()
                && br.getAddress(0).equals(lo()) && br.getServerAppId(0) == APP, "browser: name (UTF-8), players, capacity, address, port");
        byte[] dd = new byte[8];
        check(br.getDataLength(0) == 3 && br.getData(0, dd, 0) == 3 && dd[0] == 9 && dd[2] == 7 && br.getPingMillis(0) >= 0 && br.getPingMillis(0) < 500 && br.getAgeMillis(0) < 1000,
                "browser: data block, ping, age");
        cl.add(client(s, APP));
        pump(() -> s.getClientCount() == 2, 3000, s, cl);
        br.query("127.0.0.1", s.getLocalPort());
        browse(() -> br.getCount() == 1 && br.getPlayers(0) == 2, 3000, s, cl, br);
        check(br.getCount() == 1 && br.getPlayers(0) == 2, "browser: a direct query refreshes the entry, no duplicate (" + br.getPlayers(0) + " players)");
        s.setInfo("renamed");
        br.query("localhost", s.getLocalPort());
        browse(() -> br.getCount() > 0 && br.getName(0).equals("renamed"), 3000, s, cl, br);
        check(br.getName(0).equals("renamed") && br.getDataLength(0) == 0, "browser: a new name and no data are taken over");
        check(s.getQueriesAnswered() >= 3, "browser: the server counts the questions it answered (" + s.getQueriesAnswered() + ")");

        ServerBrowser other = ServerBrowser.create(APP + 5);
        ServerBrowser any = ServerBrowser.create(0);
        other.query("127.0.0.1", s.getLocalPort());
        any.query("127.0.0.1", s.getLocalPort());
        browse(() -> any.getCount() == 1, 3000, s, cl, other, any);
        pump(() -> false, 200, s, cl);
        other.update();
        check(other.getCount() == 0 && any.getCount() == 1 && any.getServerAppId(0) == APP, "browser: only servers of its own appId are listed, appId 0 lists all");

        s.setDiscovery(false);
        ServerBrowser late = ServerBrowser.create(APP);
        late.query("127.0.0.1", s.getLocalPort());
        pump(() -> false, 500, s, cl);
        late.update();
        check(late.getCount() == 0, "browser: a server with discovery off does not answer");
        s.setDiscovery(true);

        br.setExpiry(300);
        s.setDiscovery(false);
        pump(() -> false, 600, s, cl);
        br.update();
        check(br.getCount() == 0, "browser: a server that stops answering leaves the list after the expiry");
        s.setDiscovery(true);
        br.setExpiry(6000);
        br.query("127.0.0.1", s.getLocalPort());
        browse(() -> br.getCount() == 1, 3000, s, cl, br);
        check(br.getCount() == 1, "browser: and is back when it answers again");
        br.clear();
        check(br.getCount() == 0, "browser: clear()");
        // raw wire of the question and of the answer
        DatagramSocket raw = new DatagramSocket(0, lo());
        rawSend(raw, s.getLocalPort(), pkt(5, 0xCAFE, 0, 0, 0, 0, connectBody(APP, new byte[0])));
        byte[] r = rawRecv(raw, s, 2000);
        check(r != null && r[2] == 6 && u32(r, 3) == 0xCAFE && u32(r, 19) == APP && u16(r, 23) == 2 && u16(r, 25) == 6 && r[27] == 7 && new String(r, 28, 7).equals("renamed") && r[35] == 0 && r.length == 36,
                "wire: QUERY -> INFO: header with the same token, appId, players, max, name, data (" + (r == null ? "none" : r.length + " bytes") + ")");
        rawSend(raw, s.getLocalPort(), pkt(5, 0xCAFE, 0, 0, 0, 0, connectBody(APP + 1, new byte[0])));
        check(rawRecv(raw, s, 300) == null, "wire: a QUERY for another game is not answered");
        rawSend(raw, s.getLocalPort(), pkt(5, 0xCAFE, 0, 0, 0, 0, connectBody(0, new byte[0])));
        check(rawRecv(raw, s, 2000) != null, "wire: a QUERY for appId 0 is answered by any game");
        raw.close();
        br.close(); other.close(); any.close(); late.close();
        s.close();
        ServerBrowser dead = ServerBrowser.create(APP);
        dead.close();
        check(!dead.isOpen() && !dead.scanLan(7777), "browser: closed -> scanLan returns false");
    }

    static void loopTests() throws Exception {
        final List<String> log = java.util.Collections.synchronizedList(new ArrayList<String>());
        final long[] ticks = {0};
        final int[] boom = {0};
        NetServer s = NetServer.listen("127.0.0.1", 0, APP, 8);
        ServerLoop loop = new ServerLoop(s, new ServerAdapter() {
            public void onStart(NetServer sv) { log.add("start"); }
            public void onJoin(NetServer sv, NetClient c) { log.add("join " + c.getId()); }
            public void onLeave(NetServer sv, NetClient c, int reason) { log.add("leave " + c.getId() + ":" + reason); }
            public void onMessage(NetServer sv, NetClient c, NetMessage m) {
                String t = str(m);
                log.add("msg " + c.getId() + " " + (t.length() > 20 ? t.length() + " bytes" : t));
                if (t.equals("explode")) { boom[0]++; throw new IllegalStateException("handler bug"); }
                if (t.equals("kickme")) { sv.kick(c, NetChannel.REASON_APP + 2); return; }
                if (m.reliable && t.startsWith("say ")) sv.broadcastReliable(NetServer.ALL, c.getId(), m.data, 0, m.length);
            }
            public void onTick(NetServer sv, long tick, float dt) { ticks[0] = tick; if (tick == 1) log.add("dt " + Math.round(dt * 1000)); }
            public void onStop(NetServer sv) { log.add("stop"); }
        }, 50).setMaxMessageSize(NetChannel.MAX_BIG);
        check(loop.getTickRate() == 50 && !loop.isRunning() && loop.getTick() == 0, "loop: before start");
        loop.start();
        check(s.awaitRunning(3000), "loop: the server is running");
        Thread.sleep(50);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        NetChannel a = client(s, APP);
        cl.add(a);
        pump(() -> a.isConnected(), 4000, null, cl);
        NetChannel b2 = client(s, APP);
        cl.add(b2);
        // the clients are driven here, the server by the loop's thread
        NetMessage m = new NetMessage();
        boolean[] gotSay = new boolean[1];
        pump(() -> a.isConnected() && b2.isConnected(), 4000, null, cl);
        a.sendReliable(b("say hello"), 0, 9);
        pump(() -> { while (b2.receive(m)) if (str(m).equals("say hello")) gotSay[0] = true; return gotSay[0]; }, 4000, null, cl);
        check(gotSay[0], "loop: a message goes through the handler and is relayed to the other client");
        check(loop.isRunning() && log.get(0).equals("start") && log.contains("join 1") && log.contains("join 2") && log.contains("msg 1 say hello") && log.contains("dt 20"),
                "loop: onStart first, onJoin, onMessage, dt of a 50 Hz tick is 20 ms (" + log + ")");
        long t1 = loop.getTick();
        Thread.sleep(500);
        long t2 = loop.getTick();
        check(t2 - t1 >= 18 && t2 - t1 <= 32 && ticks[0] >= t2 && loop.getLoad() < 0.5f, "loop: about 50 ticks a second (" + (t2 - t1) + " in 0.5 s), load " + loop.getLoad());
        a.sendReliable(b("explode"), 0, 7);
        pump(() -> boom[0] == 1, 3000, null, cl);
        long t3 = loop.getTick();
        Thread.sleep(150);
        check(boom[0] == 1 && loop.getTick() > t3 && loop.isRunning(), "loop: an exception in a handler is reported and the loop goes on");
        byte[] bigmsg = new byte[5000];
        a.sendReliableBig(bigmsg, 0, bigmsg.length);
        pump(() -> log.contains("msg 1 5000 bytes"), 5000, null, cl);
        check(log.contains("msg 1 5000 bytes"), "loop: setMaxMessageSize lets big messages through");
        // kick from a handler
        b2.sendReliable(b("kickme"), 0, 6);
        pump(() -> b2.getState() == NetChannel.FAILED, 4000, null, cl);
        check(b2.getCloseReason() == NetChannel.REASON_APP + 2, "loop: a handler can kick");
        pump(() -> log.contains("leave 2:" + NetServer.LEAVE_KICKED), 2000, null, cl);
        check(log.contains("leave 2:" + NetServer.LEAVE_KICKED), "loop: onLeave with LEAVE_KICKED");
        // messages and leaving in the same tick: the message comes first
        a.sendReliable(b("bye 1"), 0, 5);
        a.sendReliable(b("bye 2"), 0, 5);
        a.update();
        a.close();
        pump(() -> log.contains("leave 1:" + NetServer.LEAVE_CLOSED), 3000, null, cl);
        int i1 = log.indexOf("msg 1 bye 1"), i2 = log.indexOf("msg 1 bye 2"), il = log.indexOf("leave 1:" + NetServer.LEAVE_CLOSED);
        check(i1 >= 0 && i2 > i1 && il > i2, "loop: the last messages of a client come before its onLeave (" + i1 + ", " + i2 + ", " + il + ")");
        // stop
        NetChannel z = client(s, APP);
        cl.add(z);
        pump(() -> z.isConnected(), 3000, null, cl);
        loop.stop();
        pump(() -> z.getState() == NetChannel.CLOSED, 3000, null, cl);
        long wait = System.currentTimeMillis() + 3000;
        while (loop.isRunning() && System.currentTimeMillis() < wait) Thread.sleep(10);
        check(!loop.isRunning() && s.getState() == NetServer.CLOSED && z.getState() == NetChannel.CLOSED, "loop: stop() ends the loop, closes the server, the clients are told");
        check(log.get(log.size() - 1).equals("stop") && log.stream().anyMatch(x -> x.endsWith(":" + NetServer.LEAVE_SHUTDOWN)), "loop: onLeave(SHUTDOWN) for the rest, onStop last (" + log.get(log.size() - 2) + ", " + log.get(log.size() - 1) + ")");

        // run() on a taken port returns the reason; stop() before run() does nothing bad
        NetServer s1 = start(2);
        NetServer taken = NetServer.listen("127.0.0.1", s1.getLocalPort(), APP, 2);
        String err = new ServerLoop(taken, new ServerAdapter() { }, 30).run();
        check(err != null && err.contains("cannot open"), "loop: run() on a taken port returns the reason (" + err + ")");
        s1.close();
        // run() on the calling thread, stopped from another one
        NetServer s4 = NetServer.listen("127.0.0.1", 0, APP, 2);
        final ServerLoop l4 = new ServerLoop(s4, new ServerAdapter() { }, 100);
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException e) { } l4.stop(); }).start();
        long st = System.currentTimeMillis();
        String e4 = l4.run();
        long took = System.currentTimeMillis() - st;
        check(e4 == null && took < 2000 && l4.getTick() >= 20 && l4.getTick() <= 40, "loop: run() blocks until stop() from another thread (" + l4.getTick() + " ticks in " + took + " ms)");
        // a server that is closed from outside ends the loop with an error text
        NetServer s5 = NetServer.listen("127.0.0.1", 0, APP, 2);
        final ServerLoop l5 = new ServerLoop(s5, new ServerAdapter() { }, 100);
        new Thread(() -> { try { Thread.sleep(200); } catch (InterruptedException e) { } s5.getSocket().close(); }).start();
        String e5 = l5.run();
        check(e5 != null && !l5.isRunning(), "loop: a socket that dies under the server ends the loop with an error (" + e5 + ")");
    }

    /** examples/RoomServer.java: a whole server program, driven by real clients. */
    static void exampleTest() throws Exception {
        NetServer s = start(8);
        ServerLoop loop = RoomServer.create(s);
        loop.start();
        List<NetChannel> cl = new ArrayList<NetChannel>();
        NetChannel ann = client(s, RoomServer.APP_ID);
        ann.setConnectData(b("ann"), 0, 3);
        cl.add(ann);
        pump(() -> ann.isConnected(), 3000, null, cl);
        NetChannel bob = client(s, RoomServer.APP_ID);
        bob.setConnectData(b("bob"), 0, 3);
        NetChannel dup = client(s, RoomServer.APP_ID);
        dup.setConnectData(b("ann"), 0, 3);
        NetChannel nameless = client(s, RoomServer.APP_ID);
        cl.add(bob); cl.add(dup); cl.add(nameless);
        pump(() -> bob.isConnected() && dup.getState() == NetChannel.FAILED && nameless.getState() == NetChannel.FAILED, 4000, null, cl);
        check(bob.isConnected() && dup.getCloseReason() == NetChannel.REASON_APP + 2 && nameless.getCloseReason() == NetChannel.REASON_APP + 1, "example: names are checked by the connect filter (taken -> 18, empty -> 17)");
        ann.sendReliable(new byte[]{1, 5}, 0, 2);
        bob.sendReliable(new byte[]{1, 5}, 0, 2);
        pump(() -> false, 200, null, cl);
        ann.sendReliable(b("\u0002hello bob"), 0, 10);
        ann.send(new byte[]{3, 0, 0, 0, 1, 0, 0, 0, 2}, 0, 9);
        NetMessage m = new NetMessage();
        boolean[] got = new boolean[3];
        pump(() -> {
            while (bob.receive(m)) {
                if (m.reliable && m.data[0] == 2 && new String(m.data, 5, m.length - 5, java.nio.charset.StandardCharsets.UTF_8).equals("hello bob") && u32(m.data, 1) == 1) got[0] = true;
                if (!m.reliable && m.length == 13 && m.data[0] == 3 && m.data[4] == 1 && m.data[8] == 2 && u32(m.data, 9) == 1) got[1] = true;
            }
            while (ann.receive(m)) got[2] = true; // the sender hears nothing of its own
            return got[0] && got[1];
        }, 4000, null, cl);
        check(got[0] && got[1] && !got[2], "example: chat and positions are relayed to the room, not back to the sender");
        ann.close();
        boolean[] left = new boolean[1];
        pump(() -> { while (bob.receive(m)) if (m.reliable && m.data[0] == 4 && u32(m.data, 1) == 1) left[0] = true; return left[0]; }, 4000, null, cl);
        check(left[0], "example: the room is told that a player left");
        bob.sendReliable(new byte[]{99, 1, 2}, 0, 3);
        check(pump(() -> bob.getState() == NetChannel.FAILED, 4000, null, cl) && bob.getCloseReason() == NetChannel.REASON_KICKED, "example: a message that breaks the protocol gets the client kicked");
        loop.stop();
        long wait = System.currentTimeMillis() + 3000;
        while (loop.isRunning() && System.currentTimeMillis() < wait) Thread.sleep(10);
        check(!loop.isRunning(), "example: the server stops");
    }

    static void engineTests() throws Exception {
        Engine engine = new Engine();
        NetServer s = engine.listenNet(0, APP, 4);
        check(s != null && s.awaitRunning(3000), "Engine.listenNet: starts a server");
        NetChannel c = engine.connectNet("127.0.0.1", s.getLocalPort(), APP);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        cl.add(c);
        check(pump(() -> c.isConnected() && s.getClientCount() == 1, 3000, s, cl), "Engine: a hosted game: the server and a client of the same engine");
        engine.releaseNet();
        check(s.getState() == NetServer.CLOSED && c.getSocket().getState() == UdpSocket.CLOSED, "Engine.releaseNet: closes servers and clients");
        check(pump(() -> false, 100, s, cl) || true, "Engine: pumping after release does no harm");
    }

    static void allocationTest() throws Exception {
        NetServer s = start(16);
        List<NetChannel> cl = new ArrayList<NetChannel>();
        connectAll(s, cl, 8);
        NetEvent ev = new NetEvent();
        while (s.pollEvent(ev)) { }
        NetMessage m = new NetMessage();
        byte[] snap = new byte[200];
        byte[] input = new byte[20];
        // warm up (JIT, first buffers), then measure the server thread only
        for (int round = 0; round < 3; round++) {
            for (int t = 0; t < 400; t++) {
                for (NetChannel c : cl) { if (t % 3 == 0) c.send(input, 0, 20); c.update(); }
                s.update();
                for (int i = 0; i < s.getClientSlots(); i++) { NetClient c = s.getClientAt(i); while (c != null && c.receive(m)) { } }
                if (t % 3 == 0) s.broadcast(NetServer.ALL, 0, snap, 0, 200);
                s.pollEvent(ev);
                Thread.sleep(0, 200000);
            }
        }
        // the clients and the server run on this thread: measure all of it (the clients' side is allocation free too)
        long a0 = allocated();
        for (int t = 0; t < 3000; t++) {
            for (int k = 0; k < cl.size(); k++) { NetChannel c = cl.get(k); if (t % 3 == 0) c.send(input, 0, 20); if (t % 50 == 0) c.sendReliable(input, 0, 8); c.update(); }
            s.update();
            for (int i = 0; i < s.getClientSlots(); i++) { NetClient c = s.getClientAt(i); while (c != null && c.receive(m)) { } }
            if (t % 3 == 0) s.broadcast(NetServer.ALL, 0, snap, 0, 200);
            if (t % 50 == 0) s.broadcastReliable(NetServer.ALL, 0, input, 0, 8);
            s.pollEvent(ev);
            s.getClient(3);
            if (t % 5 == 0) java.util.concurrent.locks.LockSupport.parkNanos(1000000L);
        }
        long total = allocated() - a0;
        check(total < 20000, "zero allocation: 3000 server ticks with 8 clients, broadcasts and reliable traffic allocated " + total + " bytes");
        check(s.getClientCount() == 8, "zero allocation: still 8 clients");
        s.close();
    }
}
