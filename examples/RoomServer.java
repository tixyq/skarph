// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

import java.nio.charset.Charset;

/**
 * Dedicated server in one file, using only skarph-core. Players connect with a name (the connect data of their channel),
 * join rooms, chat within a room and exchange positions. Uses {@link NetServer} with a {@link ServerLoop}, a connect
 * filter, rooms, relaying of reliable and unreliable messages and kicking.
 *
 * Build and run (from the folder that has skarph-core/):
 *   javac -d /tmp/room -sourcepath skarph-core/src examples/RoomServer.java
 *   java -cp /tmp/room RoomServer 7777 32           port, maximum number of players; type "stop" or press Ctrl+C to end it
 *
 * The protocol (the first byte of a message says what it is):
 *   client -> server, reliable:   1 + room (u8)           join a room
 *                                 2 + text                say something in your room
 *   client -> server, unreliable: 3 + x (f32) + y (f32)   my position; the server passes it on to the room as 3 + x + y + id (u32)
 *   server -> client, reliable:   2 + id (u32) + text     somebody said something
 *                                 4 + id (u32)            somebody left your room (or the server: everybody it knew of)
 *   The client connects with engine.connectNet(host, 7777, APP_ID), after setConnectData(name bytes, ...): the name of a
 *   player is its connect data; an empty one or one that is used is refused with REASON_APP + 1 / REASON_APP + 2.
 */
public class RoomServer extends ServerAdapter {
    static final int APP_ID = 0x5A01;
    static final Charset UTF8 = Charset.forName("UTF-8");

    private final byte[] out = new byte[NetChannel.MAX_RELIABLE];

    /** What the server keeps of a player (NetClient.setUserData). */
    static final class Player {
        final String name;
        Player(String name) {
            this.name = name;
        }
    }

    private static void put32(byte[] d, int p, int v) {
        d[p] = (byte) (v >> 24);
        d[p + 1] = (byte) (v >> 16);
        d[p + 2] = (byte) (v >> 8);
        d[p + 3] = (byte) v;
    }

    @Override
    public void onStart(NetServer server) {
        System.out.println("room server on port " + server.getLocalPort() + ", up to " + server.getMaxClients() + " players");
    }

    @Override
    public void onJoin(NetServer server, NetClient client) {
        byte[] name = new byte[NetChannel.MAX_CONNECT_DATA];
        int n = client.getConnectData(name, 0);
        client.setUserData(new Player(new String(name, 0, n, UTF8)));
        System.out.println(((Player) client.getUserData()).name + " joined (" + server.getClientCount() + " players)");
        server.setInfo("Rooms: " + server.getClientCount() + " players");
    }

    @Override
    public void onLeave(NetServer server, NetClient client, int reason) {
        Player p = (Player) client.getUserData();
        System.out.println((p == null ? client.toString() : p.name) + " left (reason " + reason + ")");
        out[0] = 4;
        put32(out, 1, client.getId());
        server.broadcastReliable(client.getRoom(), client.getId(), out, 0, 5);
        server.setInfo("Rooms: " + server.getClientCount() + " players");
    }

    @Override
    public void onMessage(NetServer server, NetClient client, NetMessage m) {
        if (m.length == 0) {
            return;
        }
        int type = m.data[0] & 255;
        if (type == 1 && m.reliable && m.length == 2) {
            client.setRoom(m.data[1] & 255);
        } else if (type == 2 && m.reliable) {
            int n = Math.min(m.length - 1, out.length - 5);
            out[0] = 2;
            put32(out, 1, client.getId());
            System.arraycopy(m.data, 1, out, 5, n);
            server.broadcastReliable(client.getRoom(), client.getId(), out, 0, 5 + n);
        } else if (type == 3 && !m.reliable && m.length == 9) {
            // relay: the same bytes with the sender's id instead of the type (the position is not looked at: a real game checks it)
            System.arraycopy(m.data, 0, out, 0, 9);
            out[0] = 3;
            put32(out, 9, client.getId());
            server.broadcast(client.getRoom(), client.getId(), out, 0, 13);
        } else {
            server.kick(client); // a message that does not follow the protocol: the client is not ours
        }
    }

    /** Sets the server up (name check, limit per address, info for the server list) and returns the loop that runs it. */
    public static ServerLoop create(final NetServer server) {
        server.setMaxClientsPerAddress(4);
        server.setInfo("Room server");
        server.setConnectFilter(new NetServer.ConnectFilter() {
            public int check(java.net.InetAddress address, int port, byte[] data, int offset, int length) {
                if (length == 0) {
                    return NetChannel.REASON_APP + 1; // a name is needed
                }
                String name = new String(data, offset, length, UTF8);
                for (int i = 0; i < server.getClientSlots(); i++) {
                    NetClient c = server.getClientAt(i);
                    if (c != null && c.getUserData() != null && ((Player) c.getUserData()).name.equals(name)) {
                        return NetChannel.REASON_APP + 2; // the name is taken
                    }
                }
                return 0;
            }
        });
        return new ServerLoop(server, new RoomServer(), 30);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7777;
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        final ServerLoop loop = create(NetServer.listen(port, APP_ID, max));
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                loop.stop();
                while (loop.isRunning()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        Thread console = new Thread() {
            public void run() {
                try {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.trim().equals("stop")) {
                            loop.stop();
                            return;
                        }
                    }
                } catch (java.io.IOException e) {
                    // no console: Ctrl+C still works
                }
            }
        };
        console.setDaemon(true);
        console.start();
        String error = loop.run();
        if (error != null) {
            System.err.println("the server could not run: " + error);
            System.exit(1);
        }
    }
}
