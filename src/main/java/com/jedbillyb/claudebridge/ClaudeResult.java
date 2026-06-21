package com.jedbillyb.claudebridge;

/**
 * Outcome of a single Claude Code invocation. Plain value object with no
 * Bukkit coupling so it can be consumed by any front-end (in-game command,
 * future Telegram/Discord bots, tests).
 */
public final class ClaudeResult {

    private final boolean success;
    private final boolean timedOut;
    private final String text;
    private final String sessionId;
    private final int exitCode;
    private final long durationMillis;
    private final String rawOutput;
    private final String errorDetail;

    private ClaudeResult(boolean success, boolean timedOut, String text, String sessionId,
                         int exitCode, long durationMillis, String rawOutput, String errorDetail) {
        this.success = success;
        this.timedOut = timedOut;
        this.text = text;
        this.sessionId = sessionId;
        this.exitCode = exitCode;
        this.durationMillis = durationMillis;
        this.rawOutput = rawOutput;
        this.errorDetail = errorDetail;
    }

    public static ClaudeResult success(String text, String sessionId, int exitCode,
                                       long durationMillis, String rawOutput) {
        return new ClaudeResult(true, false, text, sessionId, exitCode, durationMillis, rawOutput, null);
    }

    public static ClaudeResult timedOut(long durationMillis, String rawOutput) {
        return new ClaudeResult(false, true, null, null, -1, durationMillis, rawOutput,
                "Claude did not respond within the configured timeout.");
    }

    public static ClaudeResult failure(String errorDetail, int exitCode, long durationMillis, String rawOutput) {
        return new ClaudeResult(false, false, null, null, exitCode, durationMillis, rawOutput, errorDetail);
    }

    public boolean success() {
        return success;
    }

    public boolean timedOut() {
        return timedOut;
    }

    /** Human-readable response text from Claude, or null if the call failed. */
    public String text() {
        return text;
    }

    /** Session id to feed back via {@code --resume}, or null if unavailable. */
    public String sessionId() {
        return sessionId;
    }

    public int exitCode() {
        return exitCode;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public String rawOutput() {
        return rawOutput;
    }

    public String errorDetail() {
        return errorDetail;
    }
}
