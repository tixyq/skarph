// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * 1.3: UDP. UdpSocket against a plain JDK DatagramSocket and NetChannel against (a) a second NetChannel (the server side of one peer),
 * (b) a hand-written peer that speaks the documented wire format byte by byte. Real sockets on the
 * loopback interface: handshake, refusal, ordering, duplicates, bit masks, reliable messages under 30 % loss, round trip
 * time, loss measuring, timeouts, keep-alive, sequence wrap-around, Engine wiring and zero allocation.
 */
public class NetTest {
    static int failed;
    static final byte[] LO = {127, 0, 0, 1};

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    interface Cond { boolean ok(); }

    static boolean waitFor(Cond c, int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            try { Thread.sleep(2); } catch (InterruptedException e) { return false; }
        }
        return c.ok();
    }

    static int freePort() throws Exception {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getByAddress(LO));
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    static String str(NetMessage m) { return new String(m.data, 0, m.length, java.nio.charset.StandardCharsets.UTF_8); }

    // ---- the wire format, written out by hand (independent of NetChannel)
    static byte[] pkt(int kind, int token, int seq, int ack, int ackBits, int ackDelay, int relAck, byte[] body) {
        byte[] d = new byte[19 + body.length];
        d[0] = 'S'; d[1] = 'K'; d[2] = (byte) kind;
        d[3] = (byte) (token >> 24); d[4] = (byte) (token >> 16); d[5] = (byte) (token >> 8); d[6] = (byte) token;
        d[7] = (byte) (seq >> 8); d[8] = (byte) seq; d[9] = (byte) (ack >> 8); d[10] = (byte) ack;
        d[11] = (byte) (ackBits >> 24); d[12] = (byte) (ackBits >> 16); d[13] = (byte) (ackBits >> 8); d[14] = (byte) ackBits;
        d[15] = (byte) (ackDelay >> 8); d[16] = (byte) ackDelay; d[17] = (byte) (relAck >> 8); d[18] = (byte) relAck;
        System.arraycopy(body, 0, d, 19, body.length);
        return d;
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    static int u16(byte[] d, int p) { return ((d[p] & 255) << 8) | (d[p + 1] & 255); }
    static int u32(byte[] d, int p) { return (u16(d, p) << 16) | u16(d, p + 2); }

    static void rawSend(DatagramSocket s, int port, byte[] d) throws Exception {
        s.send(new DatagramPacket(d, d.length, InetAddress.getByAddress(LO), port));
    }

    static byte[] rawRecv(DatagramSocket s, int ms) throws Exception {
        s.setSoTimeout(ms);
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            s.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        byte[] r = new byte[p.getLength()];
        System.arraycopy(buf, 0, r, 0, r.length);
        return r;
    }

    /** Runs update() on the channels every 2 ms until the condition holds. */
    static boolean pump(Cond c, int ms, NetChannel... chans) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            for (NetChannel ch : chans) ch.update();
            if (c.ok()) return true;
            try { Thread.sleep(2); } catch (InterruptedException e) { return false; }
        }
        for (NetChannel ch : chans) ch.update();
        return c.ok();
    }

    /** A connected pair on the loopback interface: the client and the server side. */
    static NetChannel[] pair(int appIdClient, int appIdServer) throws Exception {
        int pc = freePort();
        int ps = freePort();
        UdpSocket cs = UdpSocket.connect("127.0.0.1", ps, pc, 128);
        UdpSocket ss = UdpSocket.connect("127.0.0.1", pc, ps, 128);
        NetChannel client = new NetChannel(cs, appIdClient);
        NetChannel server = NetTestHook.accept(ss, appIdServer, true);
        return new NetChannel[]{client, server};
    }

    static void closePair(NetChannel[] p) {
        p[0].close();
        p[1].close();
        p[0].getSocket().close();
        p[1].getSocket().close();
    }

    public static void main(String[] args) throws Exception {
        socketTests();
        pairTests();
        rawServerTests();
        rawClientTests();
        timeoutTests();
        lossyTests();
        wrapTest();
        seqWrapTest();
        engineTests();
        allocationTest();
        if (failed > 0) {
            System.out.println(failed + " NET TEST(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL NET TESTS PASSED");
        System.exit(0); // daemon threads of closed sockets may still be winding down
    }

    // ------------------------------------------------------------------------------------------------ UdpSocket
    static void socketTests() throws Exception {
        final DatagramSocket server = new DatagramSocket(0, InetAddress.getByAddress(LO));
        int sp = server.getLocalPort();
        final UdpSocket u = UdpSocket.connect("127.0.0.1", sp);
        check(waitFor(new Cond() { public boolean ok() { return u.isOpen(); } }, 2000), "UdpSocket: opens (CONNECTING -> OPEN)");
        check(u.getLocalPort() > 0 && u.getRemoteAddress() != null && u.getPort() == sp, "UdpSocket: local port, remote address, remote port");
        byte[] hello = b("hello");
        check(u.send(hello, 0, 5), "UdpSocket: send is queued");
        byte[] got = rawRecv(server, 2000);
        check(got != null && new String(got).equals("hello"), "UdpSocket: the server gets the datagram");
        // the server answers to the client's port
        byte[] ans = b("welcome");
        server.send(new DatagramPacket(ans, ans.length, InetAddress.getByAddress(LO), u.getLocalPort()));
        final UdpPacket in = new UdpPacket();
        check(waitFor(new Cond() { public boolean ok() { return u.receive(in); } }, 2000), "UdpSocket: receive gets the answer");
        check(new String(in.data, 0, in.length).equals("welcome") && in.port == sp && in.address != null
                && in.receivedNanos != 0, "UdpSocket: packet content, sender port, address and arrival time");
        check(!u.receive(in), "UdpSocket: nothing more to receive");
        check(u.getPacketsSent() == 1 && u.getBytesSent() == 5 && u.getPacketsReceived() == 1 && u.getBytesReceived() == 7,
                "UdpSocket: counters (sent 1/5 bytes, received 1/7 bytes)");
        check(!u.send(new byte[UdpSocket.MAX_PACKET + 1], 0, UdpSocket.MAX_PACKET + 1), "UdpSocket: a datagram above MAX_PACKET is refused");
        check(!u.send(hello, 3, 5) && !u.send(null, 0, 0) && !u.send(hello, -1, 2), "UdpSocket: bad ranges are refused");
        check(u.send(hello, 0, 0), "UdpSocket: an empty datagram is allowed");
        // a datagram from another address is not accepted (the socket is connected to the server only)
        DatagramSocket other = new DatagramSocket(0, InetAddress.getByAddress(LO));
        other.send(new DatagramPacket(ans, ans.length, InetAddress.getByAddress(LO), u.getLocalPort()));
        Thread.sleep(150);
        check(!u.receive(in), "UdpSocket: only the connected peer is heard");
        other.close();
        // an oversized datagram from the server is dropped and counted
        byte[] big = new byte[UdpSocket.MAX_PACKET + 50];
        server.send(new DatagramPacket(big, big.length, InetAddress.getByAddress(LO), u.getLocalPort()));
        check(waitFor(new Cond() { public boolean ok() { return u.getDroppedIn() == 1; } }, 2000) && !u.receive(in),
                "UdpSocket: a datagram bigger than MAX_PACKET is dropped and counted");

        // bounded queues
        u.setSimulation(0, 800);
        int before = (int) u.getPacketsSent();
        final UdpSocket tiny = UdpSocket.connect("127.0.0.1", sp, 0, 4);
        waitFor(new Cond() { public boolean ok() { return tiny.isOpen(); } }, 2000);
        tiny.setSimulation(0, 800);
        int queued = 0;
        for (int i = 0; i < 7; i++) if (tiny.send(hello, 0, 5)) queued++;
        check(queued == 4 && tiny.getDroppedOut() == 3, "UdpSocket: a full send queue refuses and counts (4 queued, 3 dropped)");
        tiny.close();
        // close() still sends what is queued, without waiting for the simulated delay
        int count = 0;
        long end = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end && count < 4) {
            if (rawRecv(server, 100) != null) count++;
        }
        check(count == 4, "UdpSocket: close() flushes the queue (" + count + " of 4 arrived)");
        check(tiny.getState() == UdpSocket.CLOSED && !tiny.send(hello, 0, 5), "UdpSocket: closed: state and send refused");

        // simulation: latency, then loss
        u.setSimulation(0, 0);
        u.setSimulation(0, 150);
        long t0 = System.nanoTime();
        u.send(hello, 0, 5);
        byte[] late = rawRecv(server, 2000);
        long ms = (System.nanoTime() - t0) / 1000000L;
        check(late != null && ms >= 140 && ms < 600, "UdpSocket: simulated latency of 150 ms (" + ms + " ms)");
        u.setSimulation(100, 0);
        for (int i = 0; i < 10; i++) u.send(hello, 0, 5);
        check(rawRecv(server, 200) == null && u.getSimulatedLost() == 10, "UdpSocket: simulated loss of 100 % drops everything");
        u.setSimulation(0, 0);

        // failures never throw
        UdpSocket bad1 = UdpSocket.connect(null, 5);
        UdpSocket bad2 = UdpSocket.connect("127.0.0.1", 0);
        UdpSocket bad3 = UdpSocket.connect("127.0.0.1", 70000);
        check(bad1.getState() == UdpSocket.FAILED && bad2.getState() == UdpSocket.FAILED && bad3.getState() == UdpSocket.FAILED
                && bad1.getError() != null && !bad1.send(hello, 0, 1), "UdpSocket: a bad host or port gives a failed socket at once");
        final UdpSocket nohost = UdpSocket.connect("no-such-host.invalid", 5000);
        check(waitFor(new Cond() { public boolean ok() { return nohost.getState() == UdpSocket.FAILED; } }, 15000)
                && nohost.getError().contains("cannot find"), "UdpSocket: an unknown server gives FAILED with a reason");
        // a port that is in use
        final UdpSocket taken = UdpSocket.connect("127.0.0.1", sp, server.getLocalPort(), 8);
        check(waitFor(new Cond() { public boolean ok() { return taken.getState() == UdpSocket.FAILED; } }, 2000)
                && taken.getError().contains("cannot open"), "UdpSocket: a local port in use gives FAILED");
        u.close();
        check(u.getState() == UdpSocket.CLOSED && u.getError() == null, "UdpSocket: close() -> CLOSED");
        u.close(); // twice is fine
        server.close();
    }

    // ------------------------------------------------------------------------------------------------ NetChannel pair
    static void pairTests() throws Exception {
        final NetChannel[] p = pair(42, 42);
        final NetChannel c = p[0], s = p[1];
        check(c.getState() == NetChannel.CONNECTING && c.getRttMillis() == -1, "NetChannel: starts CONNECTING, no round trip time yet");
        check(pump(new Cond() { public boolean ok() { return c.isConnected() && s.isConnected(); } }, 3000, c, s),
                "NetChannel: handshake (CONNECT / ACCEPT) connects both sides");
        check(!s.sendReliable(new byte[0], 0, 0) && !s.sendReliable(new byte[NetChannel.MAX_RELIABLE + 1], 0, NetChannel.MAX_RELIABLE + 1),
                "NetChannel: a reliable message must be 1.." + NetChannel.MAX_RELIABLE + " bytes");
        check(!c.send(new byte[NetChannel.MAX_PAYLOAD + 1], 0, NetChannel.MAX_PAYLOAD + 1), "NetChannel: an unreliable message above MAX_PAYLOAD is refused");

        final NetMessage m = new NetMessage();
        for (int i = 0; i < 10; i++) c.send(b("msg" + i), 0, 4);
        final int[] n = {0};
        boolean inOrder = true;
        long end = System.currentTimeMillis() + 3000;
        while (n[0] < 10 && System.currentTimeMillis() < end) {
            c.update(); s.update();
            while (s.receive(m)) {
                if (!str(m).equals("msg" + n[0]) || m.reliable) inOrder = false;
                n[0]++;
            }
            Thread.sleep(2);
        }
        check(n[0] == 10 && inOrder, "NetChannel: 10 unreliable messages arrive in order, flagged unreliable (" + n[0] + ")");

        // maximum payload
        byte[] full = new byte[NetChannel.MAX_PAYLOAD];
        for (int i = 0; i < full.length; i++) full[i] = (byte) (i * 7);
        check(c.send(full, 0, full.length), "NetChannel: a MAX_PAYLOAD message is sent");
        check(pump(new Cond() { public boolean ok() { return s.receive(m); } }, 2000, c, s) && m.length == full.length
                && java.util.Arrays.equals(java.util.Arrays.copyOf(m.data, m.length), full), "NetChannel: and arrives whole (" + (NetChannel.MAX_PAYLOAD + NetChannel.HEADER + 1) + " byte datagram)");

        // reliable both ways
        c.sendReliable(b("one"), 0, 3);
        c.sendReliable(b("two"), 0, 3);
        s.sendReliable(b("down"), 0, 4);
        final int[] got = {0, 0};
        final String[] seq = {"", ""};
        check(pump(new Cond() { public boolean ok() {
            while (s.receive(m)) { if (m.reliable) { seq[0] += str(m) + ","; got[0]++; } }
            while (c.receive(m)) { if (m.reliable) { seq[1] += str(m) + ","; got[1]++; } }
            return got[0] == 2 && got[1] == 1 && c.getReliablePending() == 0 && s.getReliablePending() == 0;
        } }, 3000, c, s), "NetChannel: reliable messages both ways, delivered and confirmed");
        check(seq[0].equals("one,two,") && seq[1].equals("down,"), "NetChannel: reliable messages keep their order (" + seq[0] + " " + seq[1] + ")");

        // keep-alive and statistics: nothing is sent by the game, the connection stays up and keeps measuring
        c.setTimeouts(8000, 700);
        s.setTimeouts(8000, 700);
        c.setKeepAlive(100);
        s.setKeepAlive(100);
        long quiet = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < quiet) { c.update(); s.update(); Thread.sleep(3); }
        check(c.isConnected() && s.isConnected(), "NetChannel: keep-alives hold an idle connection (idle timeout 700 ms, idle 1500 ms)");
        int rtt = c.getRttMillis();
        check(rtt >= 0 && rtt < 100, "NetChannel: round trip time on the loopback interface is small (" + rtt + " ms)");
        check(c.getPacketsSent() > 10 && c.getPacketsReceived() > 10 && c.getBytesSent() > 100 && c.getBytesReceived() > 100
                && c.getPacketLoss() < 0.05f && c.getSilenceMillis() < 500, "NetChannel: counters, loss ~0, silence small");

        // closing from one side
        c.close();
        check(c.getState() == NetChannel.CLOSED && c.getError() == null && !c.send(b("x"), 0, 1) && !c.sendReliable(b("x"), 0, 1),
                "NetChannel: close() -> CLOSED, nothing can be sent");
        check(pump(new Cond() { public boolean ok() { return s.getState() == NetChannel.CLOSED; } }, 2000, s),
                "NetChannel: the other side learns about the close (CLOSE datagram)");
        closePair(p);

        // wrong appId
        final NetChannel[] q = pair(1, 2);
        check(pump(new Cond() { public boolean ok() { return q[0].getState() == NetChannel.FAILED; } }, 3000, q[0], q[1]),
                "NetChannel: a server with another appId refuses the client");
        check(q[0].getError().contains("refused") && q[1].getState() == NetChannel.CONNECTING, "NetChannel: the client learns why, the server keeps waiting");
        closePair(q);

        // reliable messages queued before the connection exists go out after it, and the queue is bounded
        final NetChannel[] r = pair(5, 5);
        int accepted = 0;
        for (int i = 0; i < 70; i++) if (r[0].sendReliable(b("q" + i), 0, 2 + (i >= 10 ? 1 : 0))) accepted++;
        check(accepted == NetChannel.RELIABLE_QUEUE, "NetChannel: at most RELIABLE_QUEUE (" + NetChannel.RELIABLE_QUEUE + ") reliable messages wait (" + accepted + ")");
        final int[] total = {0};
        final boolean[] order = {true};
        pump(new Cond() { public boolean ok() {
            while (r[1].receive(m)) { if (!str(m).equals("q" + total[0])) order[0] = false; total[0]++; }
            return total[0] == NetChannel.RELIABLE_QUEUE;
        } }, 5000, r[0], r[1]);
        check(total[0] == NetChannel.RELIABLE_QUEUE && order[0], "NetChannel: queued before connecting, all " + total[0] + " arrive in order");
        pump(new Cond() { public boolean ok() { return r[0].getReliablePending() == 0; } }, 2000, r[0], r[1]);
        check(r[0].getReliablePending() == 0 && r[0].sendReliable(b("again"), 0, 5), "NetChannel: after the confirmations the queue has room again");
        closePair(r);
    }

    // ------------------------------------------------------------------------------------------------ hand-written client vs NetChannel
    static void rawServerTests() throws Exception {
        final DatagramSocket raw = new DatagramSocket(0, InetAddress.getByAddress(LO));
        int ps = freePort();
        final UdpSocket ss = UdpSocket.connect("127.0.0.1", raw.getLocalPort(), ps, 64);
        final NetChannel s = NetTestHook.accept(ss, 77, true);
        waitFor(new Cond() { public boolean ok() { return ss.isOpen(); } }, 2000); // a datagram sent before that would find nobody
        final int tok = 0x11223344;
        // wrong appId is refused with a CLOSE (reason 1)
        rawSend(raw, ps, pkt(1, tok, 0, 0, 0, 0, 0, new byte[]{0, 0, 0, 5}));
        pump(new Cond() { public boolean ok() { return false; } }, 100, s);
        byte[] a = rawRecv(raw, 1000);
        check(a != null && a.length == 20 && a[2] == 4 && u32(a, 3) == tok && a[19] == 1, "wire: CONNECT with a wrong appId is answered with CLOSE, reason 1");
        check(s.getState() == NetChannel.CONNECTING, "wire: and does not connect");
        // right appId
        rawSend(raw, ps, pkt(1, tok, 0, 0, 0, 0, 0, new byte[]{0, 0, 0, 77}));
        check(pump(new Cond() { public boolean ok() { return s.isConnected(); } }, 2000, s), "wire: CONNECT with the right appId connects");
        a = rawRecv(raw, 1000);
        check(a != null && a.length == 19 && a[0] == 'S' && a[1] == 'K' && a[2] == 2 && u32(a, 3) == tok, "wire: the answer is ACCEPT with the same token");
        // malformed datagrams are counted and ignored
        rawSend(raw, ps, new byte[]{1, 2, 3});
        rawSend(raw, ps, pkt(3, tok + 1, 1, 0, 0, 0, 0, cat(new byte[]{0}, b("data"))));
        byte[] badMagic = pkt(3, tok, 1, 0, 0, 0, 0, cat(new byte[]{0}, b("data")));
        badMagic[0] = 'X';
        rawSend(raw, ps, badMagic);
        // data: 5, 3 (late), 5 (again), 6
        rawSend(raw, ps, pkt(3, tok, 5, 0, 0, 0, 0, cat(new byte[]{0}, b("a"))));
        rawSend(raw, ps, pkt(3, tok, 3, 0, 0, 0, 0, cat(new byte[]{0}, b("late"))));
        rawSend(raw, ps, pkt(3, tok, 5, 0, 0, 0, 0, cat(new byte[]{0}, b("a"))));
        rawSend(raw, ps, pkt(3, tok, 6, 0, 0, 0, 0, cat(new byte[]{0}, b("b"))));
        final NetMessage m = new NetMessage();
        final StringBuilder seen = new StringBuilder();
        pump(new Cond() { public boolean ok() {
            while (s.receive(m)) seen.append(str(m)).append(',');
            return seen.toString().equals("a,b,") && s.getBadPackets() >= 3;
        } }, 2000, s);
        check(seen.toString().equals("a,b,"), "wire: the late and the doubled datagram are not delivered (got " + seen + ")");
        check(s.getBadPackets() == 3 && s.getDuplicates() == 1, "wire: 3 foreign datagrams and 1 duplicate counted (" + s.getBadPackets() + ", " + s.getDuplicates() + ")");
        // what the channel says back: ack = 6, bit mask: 5 -> bit 0, 3 -> bit 2
        s.send(b("x"), 0, 1);
        pump(new Cond() { public boolean ok() { return false; } }, 30, s);
        byte[] r = null;
        for (int i = 0; i < 5 && (r == null || r.length != 21); i++) r = rawRecv(raw, 500);
        check(r != null && r[2] == 3 && u16(r, 9) == 6 && u32(r, 11) == 5 && r[19] == 0 && r[20] == 'x',
                "wire: header says ack 6, mask 0b101 (5 and 3 received, 4 missing), no reliable, payload x");
        // reliable from the raw side: id 0 "hey", then id 0 again, then id 5 (a gap)
        rawSend(raw, ps, pkt(3, tok, 7, 0, 0, 0, 0, cat(new byte[]{1, 0, 0, 0, 3}, b("hey"))));
        rawSend(raw, ps, pkt(3, tok, 8, 0, 0, 0, 0, cat(new byte[]{1, 0, 0, 0, 3}, b("hey"))));
        rawSend(raw, ps, pkt(3, tok, 9, 0, 0, 0, 0, cat(new byte[]{1, 0, 5, 0, 2}, b("zz"))));
        seen.setLength(0);
        pump(new Cond() { public boolean ok() {
            while (s.receive(m)) seen.append(str(m)).append(m.reliable ? "R" : "u").append(',');
            return false;
        } }, 200, s);
        check(seen.toString().equals("heyR,"), "wire: reliable id 0 is delivered once; a repeat and a gap are not (" + seen + ")");
        // the channel confirms by relAck = 1 soon (no game traffic needed) and then stops asking
        boolean ackSeen = false;
        for (int i = 0; i < 20 && !ackSeen; i++) {
            pump(new Cond() { public boolean ok() { return false; } }, 10, s);
            byte[] q = rawRecv(raw, 20);
            while (q != null) { if (q.length >= 19 && q[2] == 3 && u16(q, 17) == 1) ackSeen = true; q = rawRecv(raw, 5); }
        }
        check(ackSeen, "wire: a received reliable message is confirmed at once, relAck = 1");
        // reliable from the channel to the raw side, repeated until confirmed
        s.sendReliable(b("srv"), 0, 3);
        int copies = 0;
        for (int i = 0; i < 40 && copies < 2; i++) {
            pump(new Cond() { public boolean ok() { return false; } }, 20, s);
            byte[] q = rawRecv(raw, 30);
            while (q != null) {
                if (q.length >= 19 + 1 + 4 + 3 && q[2] == 3 && q[19] == 1 && u16(q, 20) == 0 && u16(q, 22) == 3 && new String(q, 24, 3).equals("srv")) copies++;
                q = rawRecv(raw, 5);
            }
        }
        check(copies >= 2 && s.getReliablePending() == 1, "wire: an unconfirmed reliable message is repeated (" + copies + " copies)");
        rawSend(raw, ps, pkt(3, tok, 10, 0, 0, 0, 1, new byte[]{0}));
        pump(new Cond() { public boolean ok() { return s.getReliablePending() == 0; } }, 1000, s);
        check(s.getReliablePending() == 0, "wire: relAck = 1 from the other side ends the repeating");
        // goodbye
        rawSend(raw, ps, pkt(4, tok, 0, 0, 0, 0, 0, new byte[]{0}));
        pump(new Cond() { public boolean ok() { return s.getState() == NetChannel.CLOSED; } }, 1000, s);
        check(s.getState() == NetChannel.CLOSED && s.getError() == null, "wire: CLOSE from the other side -> CLOSED");
        s.close();
        raw.close();
    }

    // ------------------------------------------------------------------------------------------------ NetChannel client vs hand-written server
    static void rawClientTests() throws Exception {
        final DatagramSocket raw = new DatagramSocket(0, InetAddress.getByAddress(LO));
        final NetChannel c = NetChannel.connect("127.0.0.1", raw.getLocalPort(), 0xCAFE);
        byte[] first = null;
        for (int i = 0; i < 40 && first == null; i++) { c.update(); first = rawRecv(raw, 25); }
        check(first != null && first.length == 23 && first[0] == 'S' && first[1] == 'K' && first[2] == 1 && u32(first, 3) != 0
                && u32(first, 19) == 0xCAFE, "wire: the client starts with CONNECT: magic, kind 1, a token, appId");
        int tok = u32(first, 3);
        // the client repeats CONNECT until answered
        byte[] second = null;
        for (int i = 0; i < 40 && second == null; i++) { c.update(); second = rawRecv(raw, 25); }
        check(second != null && second[2] == 1 && u32(second, 3) == tok, "wire: CONNECT is repeated with the same token");
        DatagramSocket sender = raw;
        int cp = c.getSocket().getLocalPort();
        check(cp > 0, "wire: the client's local port is known");
        rawSend(sender, cp, pkt(2, tok + 9, 0, 0, 0, 0, 0, new byte[0]));
        pump(new Cond() { public boolean ok() { return false; } }, 60, c);
        check(c.getState() == NetChannel.CONNECTING, "wire: an ACCEPT with a wrong token is ignored");
        rawSend(sender, cp, pkt(2, tok, 0, 0, 0, 0, 0, new byte[0]));
        check(pump(new Cond() { public boolean ok() { return c.isConnected(); } }, 1000, c), "wire: ACCEPT with the token connects the client");
        // server sends data, with a reliable message
        rawSend(sender, cp, pkt(3, tok, 1, 0, 0, 0, 0, cat(new byte[]{1, 0, 0, 0, 2}, b("hi"), b("state"))));
        final NetMessage m = new NetMessage();
        final StringBuilder seen = new StringBuilder();
        pump(new Cond() { public boolean ok() {
            while (c.receive(m)) seen.append(str(m)).append(m.reliable ? "R" : "u").append(',');
            return seen.length() >= 12;
        } }, 1000, c);
        check(seen.toString().equals("hiR,stateu,"), "wire: reliable and unreliable parts of one datagram are both delivered (" + seen + ")");
        // the client confirms
        c.send(b("pos"), 0, 3);
        byte[] r = null;
        long end = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end) {
            c.update();
            byte[] q = rawRecv(raw, 20);
            if (q != null && q.length >= 22 && q[2] == 3 && new String(q, q.length - 3, 3).equals("pos")) { r = q; break; }
        }
        check(r != null && u16(r, 9) == 1 && u16(r, 17) == 1 && u16(r, 7) >= 1 && r[19] == 0, "wire: the client's data has ack 1 and relAck 1");
        // the server closes
        rawSend(sender, cp, pkt(4, tok, 0, 0, 0, 0, 0, new byte[]{0}));
        pump(new Cond() { public boolean ok() { return c.getState() == NetChannel.CLOSED; } }, 1000, c);
        check(c.getState() == NetChannel.CLOSED && c.getSocket().getState() == UdpSocket.CLOSED, "wire: CLOSE from the server -> CLOSED, the owned socket is closed");
        raw.close();
    }

    // ------------------------------------------------------------------------------------------------ timeouts
    static void timeoutTests() throws Exception {
        // nobody answers
        final DatagramSocket mute = new DatagramSocket(0, InetAddress.getByAddress(LO));
        final NetChannel c = NetChannel.connect("127.0.0.1", mute.getLocalPort(), 1);
        c.setTimeouts(400, 400);
        long t0 = System.currentTimeMillis();
        check(pump(new Cond() { public boolean ok() { return c.getState() == NetChannel.FAILED; } }, 3000, c)
                && c.getError().contains("no answer") && System.currentTimeMillis() - t0 >= 350, "timeout: no answer to CONNECT -> FAILED after the connect timeout");
        check(c.getSocket().getState() == UdpSocket.CLOSED, "timeout: the owned socket is closed");
        mute.close();
        // the server connects and then goes silent
        final NetChannel[] p = pair(3, 3);
        pump(new Cond() { public boolean ok() { return p[0].isConnected() && p[1].isConnected(); } }, 3000, p[0], p[1]);
        p[0].setTimeouts(5000, 500);
        t0 = System.currentTimeMillis();
        final NetChannel cl = p[0];
        check(pump(new Cond() { public boolean ok() { return cl.getState() == NetChannel.FAILED; } }, 3000, cl)
                && cl.getError().contains("timed out") && System.currentTimeMillis() - t0 >= 450, "timeout: a silent server -> FAILED after the idle timeout");
        check(!cl.send(b("x"), 0, 1) && cl.getError() != null && cl.receive(new NetMessage()) == false, "timeout: a failed channel sends nothing");
        closePair(p);
        // a dead socket fails the channel
        UdpSocket us = UdpSocket.connect("no-such-host.invalid", 4000);
        final NetChannel d = new NetChannel(us, 1);
        check(pump(new Cond() { public boolean ok() { return d.getState() == NetChannel.FAILED; } }, 15000, d) && d.getError().contains("cannot find"),
                "timeout: a socket that cannot start fails the channel with its error");
        // channels on a failed / bad address
        NetChannel e = NetChannel.connect(null, 0, 1);
        e.update();
        check(e.getState() == NetChannel.FAILED && e.getError() != null, "NetChannel.connect with a bad address -> FAILED, no exception");
    }

    // ------------------------------------------------------------------------------------------------ 30 % loss, latency
    static void lossyTests() throws Exception {
        final NetChannel[] p = pair(9, 9);
        final NetChannel c = p[0], s = p[1];
        pump(new Cond() { public boolean ok() { return c.isConnected() && s.isConnected(); } }, 3000, c, s);
        c.getSocket().setSimulation(30, 40);
        s.getSocket().setSimulation(30, 40);
        c.setTimeouts(8000, 20000);
        s.setTimeouts(8000, 20000);
        final int N = 60;
        int sentC = 0, sentS = 0;
        final int[] gotS = {0}, gotC = {0};
        final boolean[] okOrder = {true};
        final NetMessage m = new NetMessage();
        long start = System.currentTimeMillis();
        int tick = 0;
        long deadline = start + 40000;
        while (System.currentTimeMillis() < deadline && (gotS[0] < N || gotC[0] < N || tick < 300)) {
            if (sentC < N && c.sendReliable(b("c" + sentC), 0, ("c" + sentC).length())) sentC++;
            if (sentS < N && s.sendReliable(b("s" + sentS), 0, ("s" + sentS).length())) sentS++;
            if (tick % 3 == 0) { c.send(b("u"), 0, 1); s.send(b("u"), 0, 1); }
            c.update(); s.update();
            while (s.receive(m)) if (m.reliable) { if (!str(m).equals("c" + gotS[0])) okOrder[0] = false; gotS[0]++; }
            while (c.receive(m)) if (m.reliable) { if (!str(m).equals("s" + gotC[0])) okOrder[0] = false; gotC[0]++; }
            tick++;
            Thread.sleep(4);
        }
        check(gotS[0] == N && gotC[0] == N && okOrder[0], "lossy: 30 % loss each way: " + N + " reliable messages each way, exactly once, in order (" + gotS[0] + ", " + gotC[0] + ", " + tick + " ticks)");
        check(c.getSocket().getSimulatedLost() > 10 && s.getSocket().getSimulatedLost() > 10, "lossy: the simulation really dropped datagrams (" + c.getSocket().getSimulatedLost() + ")");
        float loss = c.getPacketLoss();
        check(loss > 0.12f && loss < 0.55f, "lossy: measured packet loss near 30 % (" + (int) (loss * 100) + " %)");
        int rtt = c.getRttMillis();
        check(rtt >= 70 && rtt < 250, "lossy: round trip time near 2 x 40 ms (" + rtt + " ms)");
        check(c.isConnected() && s.isConnected(), "lossy: still connected");
        closePair(p);
    }

    // ------------------------------------------------------------------------------------------------ wrap-around
    static void wrapTest() throws Exception {
        final NetChannel[] p = pair(11, 11);
        final NetChannel c = p[0], s = p[1];
        pump(new Cond() { public boolean ok() { return c.isConnected() && s.isConnected(); } }, 3000, c, s);
        c.setTimeouts(8000, 30000);
        s.setTimeouts(8000, 30000);
        final int TOTAL = 70000;

        final byte[] buf = new byte[4];
        final NetMessage m = new NetMessage();
        int sent = 0, got = 0;
        boolean ordered = true;
        long deadline = System.currentTimeMillis() + 60000;
        while (got < TOTAL && System.currentTimeMillis() < deadline) {
            while (sent < TOTAL) {
                buf[0] = (byte) (sent >> 24); buf[1] = (byte) (sent >> 16); buf[2] = (byte) (sent >> 8); buf[3] = (byte) sent;
                if (!c.sendReliable(buf, 0, 4)) break;
                sent++;
            }
            if ((sent & 15) == 0) c.send(buf, 0, 1);
            c.update(); s.update();
            while (s.receive(m)) {
                if (!m.reliable) continue;
                int v = u32(m.data, 0);
                if (v != got) ordered = false;
                got++;
            }
            Thread.sleep(1); // a tick, not a busy loop: a flood only fills the queues and everything is repeated
        }
        check(got == TOTAL && ordered, "wrap: " + TOTAL + " reliable messages in order across the 16 bit id wrap-around (" + got + ")");
        closePair(p);
    }

    static void seqWrapTest() throws Exception {
        final NetChannel[] p = pair(12, 12);
        final NetChannel c = p[0], s = p[1];
        NetTestHook.setSequence(c, 65496);   // before the first datagram: the other side takes the first number it sees
        NetTestHook.setSequence(s, 65500);
        pump(new Cond() { public boolean ok() { return c.isConnected() && s.isConnected(); } }, 3000, c, s);
        c.setTimeouts(8000, 30000);
        s.setTimeouts(8000, 30000);
        final byte[] buf = new byte[4];
        final NetMessage m = new NetMessage();
        // talk both ways: the numbers wrap and the acks keep working
        int gotC = 0, gotS = 0;
        for (int i = 0; i < 200; i++) {
            c.send(buf, 0, 1);
            s.send(buf, 0, 2);
            c.update(); s.update();
            while (s.receive(m)) gotS++;
            while (c.receive(m)) gotC++;
            Thread.sleep(3);
        }
        pump(new Cond() { public boolean ok() { return false; } }, 50, c, s);
        while (s.receive(m)) gotS++;
        while (c.receive(m)) gotC++;
        check(gotS == 200 && gotC == 200 && c.getDuplicates() == 0 && s.getDuplicates() == 0,
                "wrap: datagram numbers wrap at 65536 (0 is skipped): 200 messages each way, no duplicates (" + gotS + ", " + gotC + ")");
        check(c.getPacketLoss() < 0.05f && s.getPacketLoss() < 0.05f && c.getRttMillis() >= 0 && c.getRttMillis() < 50,
                "wrap: acks and loss accounting keep working across the wrap (loss " + (int) (c.getPacketLoss() * 100) + " %, RTT " + c.getRttMillis() + " ms)");
        // one-way flood: the receiver sends an answer after 16 datagrams, so the sender still learns what arrived
        for (int i = 0; i < 3000; i++) {
            c.send(buf, 0, 1);
            c.update(); s.update();
            while (s.receive(m)) { }
            if ((i & 3) == 0) Thread.sleep(1);
        }
        pump(new Cond() { public boolean ok() { return false; } }, 50, c, s);
        check(c.getPacketLoss() < 0.2f, "flood: one-way traffic of 3000 datagrams is acknowledged often enough (loss " + (int) (c.getPacketLoss() * 100) + " %)");
        closePair(p);
    }

    // ------------------------------------------------------------------------------------------------ Engine
    static void engineTests() throws Exception {
        Engine e = new Engine();
        check(Engine.VERSION.equals("1.3"), "Engine.VERSION is 1.3");
        final DatagramSocket raw = new DatagramSocket(0, InetAddress.getByAddress(LO));
        UdpSocket u = e.connectUdp("127.0.0.1", raw.getLocalPort());
        NetChannel c = e.connectNet("127.0.0.1", raw.getLocalPort(), 3);
        check(u != null && c != null && c.getSocket() != u, "Engine: connectUdp and connectNet return sockets / channels");
        Thread.sleep(100);
        e.releaseNet();
        check(u.getState() == UdpSocket.CLOSED && c.getSocket().getState() == UdpSocket.CLOSED,
                "Engine.releaseNet() closes every UDP socket");
        c.update();
        check(c.getState() == NetChannel.FAILED, "Engine.releaseNet(): a channel on a closed socket fails with a reason (" + c.getError() + ")");
        UdpSocket bad = e.connectUdp("", 5);
        check(bad.getState() == UdpSocket.FAILED, "Engine.connectUdp with a bad host: failed socket, no exception");
        raw.close();
    }

    // ------------------------------------------------------------------------------------------------ no allocation
    static void allocationTest() throws Exception {
        final NetChannel[] p = pair(21, 21);
        final NetChannel c = p[0], s = p[1];
        pump(new Cond() { public boolean ok() { return c.isConnected() && s.isConnected(); } }, 3000, c, s);
        c.setTimeouts(8000, 30000);
        s.setTimeouts(8000, 30000);
        byte[] data = new byte[200];
        byte[] rel = new byte[40];
        NetMessage m = new NetMessage();
        UdpPacket up = new UdpPacket();
        for (int i = 0; i < 300; i++) { // warm up
            c.send(data, 0, data.length); s.send(data, 0, 100); c.update(); s.update();
            while (c.receive(m)) { }
            while (s.receive(m)) { }
        }
        allocated(); allocated();
        long a0 = allocated();
        int relSent = 0;
        for (int i = 0; i < 5000; i++) {
            if (i % 10 == 0) { c.send(data, 0, data.length); s.send(data, 0, 100); }
            if (i % 25 == 0 && c.sendReliable(rel, 0, rel.length)) relSent++;
            c.update(); s.update();
            while (c.receive(m)) { }
            while (s.receive(m)) { }
            if (i % 50 == 0) { c.getRttMillis(); c.getPacketLoss(); c.getSilenceMillis(); c.getSocket().receive(up); }
            if (i % 8 == 0) Thread.sleep(0, 200000);
        }
        long d = allocated() - a0;
        check(d < 2048, "zero allocation: 5000 ticks of update, send, sendReliable, receive on both channels (" + d + " bytes, " + relSent + " reliable)");
        check(c.isConnected() && s.isConnected(), "zero allocation run: still connected");
        closePair(p);
    }
}
