package com.jedbillyb.claudebridge;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Handles {@code /claude <message>}. Responsibility is kept thin: validate
 * input, enforce permission/cooldown, then hand the actual work to
 * {@link ClaudeService} on an async thread. All Claude logic lives in the
 * service so other front-ends (Telegram/Discord) can reuse it untouched.
 */
public final class ClaudeCommand implements CommandExecutor {

    // Stable, fixed UUID so console invocations get their own session slot.
    private static final UUID CONSOLE_ID = new UUID(0L, 0L);

    private final ClaudeBridgePlugin plugin;
    private final ClaudeService service;
    private final SessionStore sessions;
    private final CooldownManager cooldowns;
    private final InvocationLogger auditLog;

    public ClaudeCommand(ClaudeBridgePlugin plugin, ClaudeService service, SessionStore sessions,
                         CooldownManager cooldowns, InvocationLogger auditLog) {
        this.plugin = plugin;
        this.service = service;
        this.sessions = sessions;
        this.cooldowns = cooldowns;
        this.auditLog = auditLog;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        BridgeConfig config = plugin.config();

        if (!sender.hasPermission(config.permissionNode())) {
            sender.sendMessage(Component.text("You don't have permission to use this.", NamedTextColor.RED));
            return true;
        }

        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /claude <message>", NamedTextColor.YELLOW));
            return true;
        }

        UUID actorId = (sender instanceof Player player) ? player.getUniqueId() : CONSOLE_ID;
        String actorName = sender.getName();

        long remaining = cooldowns.remainingSeconds(actorId, config.cooldownSeconds());
        if (remaining > 0) {
            sender.sendMessage(Component.text("Slow down — try again in " + remaining + "s.",
                    NamedTextColor.YELLOW));
            return true;
        }
        cooldowns.markUsed(actorId);

        BukkitTask thinking = startThinkingIndicator(sender);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String resumeId = sessions.get(actorId).orElse(null);
            ClaudeResult result = service.invoke(message, resumeId);

            // Audit on the worker thread (file I/O is thread-safe in the logger).
            auditLog.log(actorName, message, result);

            if (result.success()) {
                sessions.put(actorId, result.sessionId());
            }

            // All player/console messaging happens back on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> {
                thinking.cancel();
                deliver(sender, result, config.maxResponseLength());
            });
        });

        return true;
    }

    /**
     * Repeats a "thinking…" action bar (or console line) until cancelled, since
     * the action bar fades after a few seconds while Claude may run much longer.
     */
    private BukkitTask startThinkingIndicator(CommandSender sender) {
        if (sender instanceof Player player) {
            Component thinking = Component.text("Claude is thinking…", NamedTextColor.GRAY);
            return Bukkit.getScheduler().runTaskTimer(plugin, () -> player.sendActionBar(thinking), 0L, 40L);
        }
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(Component.text("Claude is thinking…", NamedTextColor.GRAY));
        }
        // Non-player senders don't get a repeating indicator; return a no-op task
        // so the caller can cancel() unconditionally.
        return Bukkit.getScheduler().runTaskLater(plugin, () -> { }, 1L);
    }

    private void deliver(CommandSender sender, ClaudeResult result, int maxLength) {
        if (result.timedOut()) {
            sender.sendMessage(Component.text("Claude timed out before responding.", NamedTextColor.RED));
            return;
        }
        if (!result.success()) {
            sender.sendMessage(Component.text("Claude error: " + result.errorDetail(), NamedTextColor.RED));
            return;
        }

        String text = result.text();
        boolean truncated = false;
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
            truncated = true;
        }

        sender.sendMessage(Component.text("[Claude] ", NamedTextColor.AQUA)
                .append(Component.text(firstLine(text), NamedTextColor.WHITE)));
        for (String line : remainingLines(text)) {
            sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
        }

        if (truncated) {
            sender.sendMessage(Component.text("… (response truncated at " + maxLength + " chars)",
                    NamedTextColor.GRAY));
        }
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static List<String> remainingLines(String text) {
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return List.of();
        }
        return List.of(text.substring(nl + 1).split("\n", -1));
    }
}
