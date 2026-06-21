package com.jedbillyb.claudebridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player rate limiter. Every /claude call spawns a real process that costs
 * money, so this throttles spam.
 */
public final class CooldownManager {

    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    /**
     * Remaining cooldown in seconds for the given player, or 0 if they may act now.
     */
    public long remainingSeconds(UUID playerId, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0;
        }
        Long last = lastUse.get(playerId);
        if (last == null) {
            return 0;
        }
        long elapsedMs = System.currentTimeMillis() - last;
        long remainingMs = TimeUnitMillis(cooldownSeconds) - elapsedMs;
        return remainingMs <= 0 ? 0 : (remainingMs + 999) / 1000;
    }

    /** Record that the player just triggered an invocation. */
    public void markUsed(UUID playerId) {
        lastUse.put(playerId, System.currentTimeMillis());
    }

    private static long TimeUnitMillis(int seconds) {
        return seconds * 1000L;
    }
}
