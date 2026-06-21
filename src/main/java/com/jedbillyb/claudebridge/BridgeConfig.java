package com.jedbillyb.claudebridge;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. Deliberately free of any Bukkit runtime
 * coupling beyond the one-time {@link #from(FileConfiguration)} read, so the
 * values can be handed to {@link ClaudeService} (which knows nothing about the
 * server) and reused by future Telegram/Discord front-ends.
 */
public final class BridgeConfig {

    private final String claudeBinary;
    private final String workingDirectory;
    private final List<String> allowedTools;
    private final int timeoutSeconds;
    private final String permissionNode;
    private final int cooldownSeconds;
    private final int maxResponseLength;
    private final String logFile;
    private final String oauthTokenFile;
    private final boolean endpointEnabled;
    private final String endpointBind;
    private final int endpointPort;
    private final String endpointToken;
    private final String rconHost;
    private final int rconPort;
    private final int rconTimeoutMillis;

    private BridgeConfig(String claudeBinary, String workingDirectory, List<String> allowedTools,
                         int timeoutSeconds, String permissionNode, int cooldownSeconds,
                         int maxResponseLength, String logFile, String oauthTokenFile,
                         boolean endpointEnabled, String endpointBind, int endpointPort,
                         String endpointToken, String rconHost, int rconPort, int rconTimeoutMillis) {
        this.claudeBinary = claudeBinary;
        this.workingDirectory = workingDirectory;
        this.allowedTools = List.copyOf(allowedTools);
        this.timeoutSeconds = timeoutSeconds;
        this.permissionNode = permissionNode;
        this.cooldownSeconds = cooldownSeconds;
        this.maxResponseLength = maxResponseLength;
        this.logFile = logFile;
        this.oauthTokenFile = oauthTokenFile;
        this.endpointEnabled = endpointEnabled;
        this.endpointBind = endpointBind;
        this.endpointPort = endpointPort;
        this.endpointToken = endpointToken;
        this.rconHost = rconHost;
        this.rconPort = rconPort;
        this.rconTimeoutMillis = rconTimeoutMillis;
    }

    public static BridgeConfig from(FileConfiguration cfg) {
        return new BridgeConfig(
                cfg.getString("claude-binary", "claude"),
                cfg.getString("working-directory", System.getProperty("user.home") + "/claude-mc-bridge"),
                cfg.getStringList("allowed-tools"),
                cfg.getInt("timeout-seconds", 60),
                cfg.getString("permission-node", "claudebridge.use"),
                cfg.getInt("cooldown-seconds", 15),
                cfg.getInt("max-response-length", 1500),
                cfg.getString("log-file", "invocations.log"),
                cfg.getString("claude-oauth-token-file", ""),
                cfg.getBoolean("command-endpoint.enabled", true),
                cfg.getString("command-endpoint.bind", "127.0.0.1"),
                cfg.getInt("command-endpoint.port", 8765),
                cfg.getString("command-endpoint.token", ""),
                cfg.getString("command-endpoint.rcon-host", "127.0.0.1"),
                cfg.getInt("command-endpoint.rcon-port", 0),
                cfg.getInt("command-endpoint.rcon-timeout-ms", 5000));
    }

    public String claudeBinary() {
        return claudeBinary;
    }

    public String workingDirectory() {
        return workingDirectory;
    }

    public List<String> allowedTools() {
        return allowedTools;
    }

    /** Comma-joined form expected by the {@code --allowedTools} flag. */
    public String allowedToolsArg() {
        return String.join(",", allowedTools);
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String permissionNode() {
        return permissionNode;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int maxResponseLength() {
        return maxResponseLength;
    }

    public String logFile() {
        return logFile;
    }

    /**
     * Path to a file containing a long-lived Claude Code OAuth token (from
     * {@code claude setup-token}). If set, its contents are injected as the
     * CLAUDE_CODE_OAUTH_TOKEN env var for the claude subprocess. Empty = rely on
     * the ambient environment instead.
     */
    public String oauthTokenFile() {
        return oauthTokenFile;
    }

    public boolean endpointEnabled() {
        return endpointEnabled;
    }

    public String endpointBind() {
        return endpointBind;
    }

    public int endpointPort() {
        return endpointPort;
    }

    /** Configured token, or empty string to mean "auto-generate at startup". */
    public String endpointToken() {
        return endpointToken;
    }

    public String rconHost() {
        return rconHost;
    }

    /** Configured RCON port, or 0 to mean "read rcon.port from server.properties". */
    public int rconPort() {
        return rconPort;
    }

    public int rconTimeoutMillis() {
        return rconTimeoutMillis;
    }
}
