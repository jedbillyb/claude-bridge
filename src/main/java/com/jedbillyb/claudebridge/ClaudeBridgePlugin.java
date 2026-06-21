package com.jedbillyb.claudebridge;

import java.nio.file.Path;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Wires the pieces together and registers /claude. Deliberately
 * lean: the reusable Claude logic lives in {@link ClaudeService}, not here.
 */
public final class ClaudeBridgePlugin extends JavaPlugin {

    private BridgeConfig config;
    private ClaudeService service;
    private SessionStore sessions;
    private CooldownManager cooldowns;
    private InvocationLogger auditLog;

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

        getLogger().info("ClaudeBridge enabled. Binary: " + config.claudeBinary()
                + ", cwd: " + config.workingDirectory()
                + ", timeout: " + config.timeoutSeconds() + "s");
    }

    @Override
    public void onDisable() {
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
