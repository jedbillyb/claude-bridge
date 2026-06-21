package com.jedbillyb.claudebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Wires the pieces together, registers /claude, and (optionally)
 * starts the localhost command endpoint Claude uses to run console commands.
 * Deliberately lean: the reusable Claude logic lives in {@link ClaudeService}.
 */
public final class ClaudeBridgePlugin extends JavaPlugin {

    private BridgeConfig config;
    private ClaudeService service;
    private SessionStore sessions;
    private CooldownManager cooldowns;
    private InvocationLogger auditLog;
    private CommandEndpoint commandEndpoint;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridgeConfig();

        this.sessions = new SessionStore();
        this.cooldowns = new CooldownManager();

        PluginCommand command = getCommand("claude");
        if (command == null) {
            getLogger().severe("Command 'claude' is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(new ClaudeCommand(this, service, sessions, cooldowns, auditLog));

        startCommandEndpoint();

        getLogger().info("ClaudeBridge enabled. Binary: " + config.claudeBinary()
                + ", cwd: " + config.workingDirectory()
                + ", timeout: " + config.timeoutSeconds() + "s");
    }

    @Override
    public void onDisable() {
        if (commandEndpoint != null) {
            commandEndpoint.stop();
            commandEndpoint = null;
        }
        if (sessions != null) {
            // Sessions are in-memory only; nothing to persist.
            sessions.clearAll();
        }
    }

    /** (Re)reads config.yml and rebuilds the config-dependent collaborators. */
    public void reloadBridgeConfig() {
        reloadConfig();
        this.config = BridgeConfig.from(getConfig());
        this.service = new ClaudeService(config);

        Path logPath = resolveLogPath(config.logFile());
        this.auditLog = new InvocationLogger(logPath, getLogger());
    }

    private void startCommandEndpoint() {
        if (!config.endpointEnabled()) {
            getLogger().info("Command endpoint disabled in config; Claude will be read-only.");
            return;
        }

        Properties serverProps = readServerProperties();
        if (!Boolean.parseBoolean(serverProps.getProperty("enable-rcon", "false"))) {
            getLogger().warning("Command endpoint enabled but enable-rcon=true is not set in "
                    + "server.properties. Console commands from Claude will fail until RCON is on.");
        }

        int rconPort = config.rconPort() > 0
                ? config.rconPort()
                : parseInt(serverProps.getProperty("rcon.port"), 25575);
        String rconPassword = serverProps.getProperty("rcon.password", "");
        if (rconPassword.isBlank()) {
            getLogger().warning("rcon.password is empty in server.properties; command endpoint "
                    + "will not be able to authenticate to RCON.");
        }

        String token = resolveToken();
        RconClient rcon = new RconClient(config.rconHost(), rconPort, rconPassword,
                config.rconTimeoutMillis());
        commandEndpoint = new CommandEndpoint(config.endpointBind(), config.endpointPort(),
                token, rcon, getLogger());
        try {
            commandEndpoint.start();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start command endpoint on "
                    + config.endpointBind() + ":" + config.endpointPort(), e);
            commandEndpoint = null;
        }
    }

    /**
     * Returns the configured token, or generates a random one and writes it to
     * {@code <working-directory>/.bridge-token} (0600) so the local Claude process
     * can read it. CLAUDE.md documents this file.
     */
    private String resolveToken() {
        String configured = config.endpointToken();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);

        Path tokenFile = Path.of(config.workingDirectory(), ".bridge-token");
        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, token + "\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tokenFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; best effort.
            }
            getLogger().info("Generated command-endpoint token at " + tokenFile);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write token file " + tokenFile
                    + "; Claude won't be able to read the token", e);
        }
        return token;
    }

    /** Reads the server's server.properties (located in the server working dir). */
    private Properties readServerProperties() {
        Properties props = new Properties();
        Path path = Path.of("server.properties");
        if (Files.isReadable(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to read server.properties", e);
            }
        } else {
            getLogger().warning("server.properties not found at " + path.toAbsolutePath()
                    + "; cannot auto-detect RCON settings.");
        }
        return props;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Path resolveLogPath(String configured) {
        Path p = Path.of(configured);
        // Relative paths land inside the plugin's data folder; absolute paths are
        // honoured as-is.
        return p.isAbsolute() ? p : getDataFolder().toPath().resolve(configured);
    }

    public BridgeConfig config() {
        return config;
    }

    public ClaudeService service() {
        return service;
    }

    public SessionStore sessions() {
        return sessions;
    }
}
