package com.jedbillyb.claudebridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the local {@code claude} binary and returns a parsed {@link ClaudeResult}.
 *
 * <p>This class is intentionally decoupled from Bukkit/Paper: it has no
 * dependency on players, schedulers, or the server at all. The in-game command
 * handler calls it from an async thread today; a Telegram or Discord bot can
 * call the exact same {@link #invoke(String, String)} method later without
 * dragging the command handler along.
 *
 * <p>{@link #invoke(String, String)} blocks until Claude returns or the timeout
 * fires, so callers are responsible for running it off the main server thread.
 */
public final class ClaudeService {

    private final BridgeConfig config;

    public ClaudeService(BridgeConfig config) {
        this.config = config;
    }

    /**
     * Invoke Claude Code with a message.
     *
     * @param message        the prompt text (must be non-blank; callers validate)
     * @param resumeSessionId previous session id to continue, or null to start fresh
     * @return the parsed result; never null. Inspect {@link ClaudeResult#success()}.
     */
    public ClaudeResult invoke(String message, String resumeSessionId) {
        List<String> command = buildCommand(message, resumeSessionId);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(config.workingDirectory()));
        // Keep stdout and stderr separate: stdout carries the JSON we parse,
        // stderr is captured only for diagnostics.
        pb.redirectErrorStream(false);

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            return ClaudeResult.failure("Failed to start '" + config.claudeBinary()
                    + "': " + e.getMessage(), -1, elapsed, "");
        }

        // Drain both streams concurrently so a full pipe buffer can never deadlock
        // the process while we wait on it.
        StreamGobbler outGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler errGobbler = new StreamGobbler(process.getErrorStream());
        Thread outThread = new Thread(outGobbler, "claude-stdout");
        Thread errThread = new Thread(errGobbler, "claude-stderr");
        outThread.start();
        errThread.start();

        boolean finished;
        try {
            finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            return ClaudeResult.failure("Invocation interrupted.", -1, elapsed, "");
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(outThread);
            joinQuietly(errThread);
            long elapsed = System.currentTimeMillis() - start;
            return ClaudeResult.timedOut(elapsed, outGobbler.content());
        }

        joinQuietly(outThread);
        joinQuietly(errThread);
        long elapsed = System.currentTimeMillis() - start;
        int exitCode = process.exitValue();
        String stdout = outGobbler.content();
        String stderr = errGobbler.content();

        if (exitCode != 0) {
            String detail = stderr.isBlank() ? "claude exited with code " + exitCode
                    : stderr.trim();
            return ClaudeResult.failure(detail, exitCode, elapsed, stdout);
        }

        return parse(stdout, exitCode, elapsed, stderr);
    }

    private List<String> buildCommand(String message, String resumeSessionId) {
        // ProcessBuilder passes args directly to exec(), so no shell quoting is
        // needed (and none must be added) around the message or tool list.
        List<String> command = new ArrayList<>();
        command.add(config.claudeBinary());
        command.add("-p");
        command.add(message);
        command.add("--output-format");
        command.add("json");
        command.add("--bare");
        command.add("--allowedTools");
        command.add(config.allowedToolsArg());
        command.add("--cwd");
        command.add(config.workingDirectory());
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            command.add("--resume");
            command.add(resumeSessionId);
        }
        return command;
    }

    private ClaudeResult parse(String stdout, int exitCode, long elapsed, String stderr) {
        if (stdout == null || stdout.isBlank()) {
            String detail = stderr.isBlank() ? "Claude returned no output." : stderr.trim();
            return ClaudeResult.failure(detail, exitCode, elapsed, stdout);
        }

        try {
            JsonObject json = JsonParser.parseString(stdout.trim()).getAsJsonObject();

            // Field names accommodate variations in the claude JSON output shape.
            String text = firstString(json, "result", "text", "response", "content");
            String sessionId = firstString(json, "session_id", "sessionId", "session");

            // Some output shapes flag errors explicitly even with exit code 0.
            boolean isError = json.has("is_error") && json.get("is_error").getAsBoolean();
            if (isError) {
                String detail = text != null ? text : "Claude reported an error.";
                return ClaudeResult.failure(detail, exitCode, elapsed, stdout);
            }

            if (text == null || text.isBlank()) {
                // Parsed JSON but couldn't find a text field; surface the raw body
                // so nothing is silently swallowed.
                return ClaudeResult.success(stdout.trim(), sessionId, exitCode, elapsed, stdout);
            }
            return ClaudeResult.success(text, sessionId, exitCode, elapsed, stdout);
        } catch (JsonSyntaxException | IllegalStateException e) {
            // Not valid JSON (or not an object): treat the raw stdout as the answer
            // rather than failing the whole call.
            return ClaudeResult.success(stdout.trim(), null, exitCode, elapsed, stdout);
        }
    }

    private static String firstString(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && json.get(key).isJsonPrimitive()) {
                String value = json.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads an output stream fully into memory on its own thread. */
    private static final class StreamGobbler implements Runnable {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        StreamGobbler(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // Stream closed (e.g. process killed on timeout); keep what we have.
            }
        }

        synchronized String content() {
            return buffer.toString();
        }
    }
}
