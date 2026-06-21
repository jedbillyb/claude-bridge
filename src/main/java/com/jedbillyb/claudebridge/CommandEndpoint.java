package com.jedbillyb.claudebridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Localhost-only HTTP endpoint that lets the local Claude process run Minecraft
 * console commands. Bound to a loopback address and guarded by a bearer token so
 * only callers that can read the token file (i.e. Claude, via its working dir)
 * can use it. Command execution + output capture is delegated to {@link RconClient}.
 *
 * <p>This is deliberately a standalone component with no command-handler coupling,
 * so a future Telegram/Discord bot (or any local tool) could drive the same API.
 *
 * <pre>
 *   POST /command   header: X-Bridge-Token: &lt;token&gt;   body: the console command
 *   GET  /health    (no auth) -&gt; "ok"
 * </pre>
 */
public final class CommandEndpoint {

    private final String bindHost;
    private final int port;
    private final String token;
    private final RconClient rcon;
    private final Logger logger;

    private HttpServer server;

    public CommandEndpoint(String bindHost, int port, String token, RconClient rcon, Logger logger) {
        this.bindHost = bindHost;
        this.port = port;
        this.token = token;
        this.rcon = rcon;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/command", this::handleCommand);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "claudebridge-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        logger.info("Command endpoint listening on http://" + bindHost + ":" + port + "/command");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "ok");
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "POST only");
                return;
            }
            String provided = ex.getRequestHeaders().getFirst("X-Bridge-Token");
            if (!tokenMatches(provided)) {
                respond(ex, 401, "unauthorized");
                return;
            }
            String command = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (command.isEmpty()) {
                respond(ex, 400, "empty command");
                return;
            }
            // Strip a leading slash if Claude includes one.
            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            String reply = rcon.execute(command);
            respond(ex, 200, reply.isEmpty() ? "(no output)" : reply);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Command endpoint failed to run command", e);
            respond(ex, 502, "command failed: " + e.getMessage());
        }
    }

    private boolean tokenMatches(String provided) {
        if (provided == null || token == null) {
            return false;
        }
        // Constant-time comparison to avoid leaking the token via timing.
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
