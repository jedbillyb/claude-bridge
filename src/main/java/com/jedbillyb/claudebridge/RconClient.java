package com.jedbillyb.claudebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Tiny stateless Minecraft RCON client. Each {@link #execute(String)} opens a
 * connection, authenticates, runs one command, and returns the server's text
 * reply. Used by {@link CommandEndpoint} so the plugin can run console commands
 * and capture their output without reimplementing a capturing CommandSender.
 *
 * <p>No Bukkit coupling, so it is unit-testable and reusable.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private final String host;
    private final int port;
    private final String password;
    private final int timeoutMillis;

    public RconClient(String host, int port, String password, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Run a single console command (no leading slash) and return its reply.
     *
     * @throws IOException on connection/auth failure
     */
    public String execute(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            send(out, 1, TYPE_AUTH, password);
            Packet auth = read(in);
            if (auth.id == -1) {
                throw new IOException("RCON authentication failed (check rcon.password)");
            }

            // Send the command, then a sentinel empty packet. The server answers
            // packets in order, so once we see the sentinel's echo we know the
            // (possibly multi-packet) command reply is complete.
            int sentinelId = 99;
            send(out, 2, TYPE_COMMAND, command);
            send(out, sentinelId, TYPE_COMMAND, "");

            StringBuilder reply = new StringBuilder();
            while (true) {
                Packet p = read(in);
                if (p.id == sentinelId) {
                    break;
                }
                if (p.type == TYPE_RESPONSE) {
                    reply.append(p.body);
                }
            }
            return reply.toString().trim();
        }
    }

    private static void send(OutputStream out, int id, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + body.length + 2; // id + type + payload + two nulls
        byte[] packet = new byte[4 + length];
        writeLE(packet, 0, length);
        writeLE(packet, 4, id);
        writeLE(packet, 8, type);
        System.arraycopy(body, 0, packet, 12, body.length);
        // last two bytes are already 0 (null payload terminator + null packet terminator)
        out.write(packet);
        out.flush();
    }

    private static Packet read(InputStream in) throws IOException {
        int length = readLE(in);
        if (length < 10) {
            throw new IOException("Malformed RCON packet (length " + length + ")");
        }
        byte[] data = readExact(in, length);
        int id = leInt(data, 0);
        int type = leInt(data, 4);
        // body runs from offset 8 to the two trailing null bytes
        String body = new String(data, 8, length - 8 - 2, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r == -1) {
                throw new IOException("RCON connection closed mid-packet");
            }
            read += r;
        }
        return buf;
    }

    private static int readLE(InputStream in) throws IOException {
        byte[] b = readExact(in, 4);
        return leInt(b, 0);
    }

    private static void writeLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static int leInt(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }

    private record Packet(int id, int type, String body) { }
}
