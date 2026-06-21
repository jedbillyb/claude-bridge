package com.jedbillyb.claudebridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends one audit record per Claude invocation to a local file. This is the
 * audit trail: timestamp, player, message, full response, exit code, duration.
 * Thread-safe so it can be called directly from async worker threads.
 */
public final class InvocationLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path logPath;
    private final Logger pluginLogger;
    private final Object lock = new Object();

    public InvocationLogger(Path logPath, Logger pluginLogger) {
        this.logPath = logPath;
        this.pluginLogger = pluginLogger;
    }

    public void log(String playerName, String message, ClaudeResult result) {
        String fullResponse = result.success()
                ? result.text()
                : "<error: " + (result.errorDetail() == null ? "unknown" : result.errorDetail()) + ">";

        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(OffsetDateTime.now().format(TS)).append(" ====").append('\n');
        sb.append("player   : ").append(playerName).append('\n');
        sb.append("message  : ").append(oneLine(message)).append('\n');
        sb.append("exitCode : ").append(result.exitCode()).append('\n');
        sb.append("duration : ").append(result.durationMillis()).append(" ms").append('\n');
        sb.append("timedOut : ").append(result.timedOut()).append('\n');
        sb.append("response :").append('\n');
        sb.append(indent(fullResponse == null ? "" : fullResponse)).append('\n');
        sb.append('\n');

        synchronized (lock) {
            try {
                Files.writeString(logPath, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                pluginLogger.log(Level.WARNING, "Failed to write invocation audit log to " + logPath, e);
            }
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String indent(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            out.append("    ").append(line).append('\n');
        }
        // Trim the trailing newline we just added.
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
