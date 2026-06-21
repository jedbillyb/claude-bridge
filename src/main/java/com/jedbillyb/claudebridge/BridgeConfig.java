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

    private BridgeConfig(String claudeBinary, String workingDirectory, List<String> allowedTools,
                         int timeoutSeconds, String permissionNode, int cooldownSeconds,
                         int maxResponseLength, String logFile) {
        this.claudeBinary = claudeBinary;
        this.workingDirectory = workingDirectory;
        this.allowedTools = List.copyOf(allowedTools);
        this.timeoutSeconds = timeoutSeconds;
        this.permissionNode = permissionNode;
        this.cooldownSeconds = cooldownSeconds;
        this.maxResponseLength = maxResponseLength;
        this.logFile = logFile;
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
                cfg.getString("log-file", "invocations.log"));
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
}
