package com.jedbillyb.claudebridge;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-player Claude session ids, used to pass {@code --resume} so
 * follow-up questions keep context. Intentionally not persisted: a fresh map on
 * server startup is the desired behaviour.
 */
public final class SessionStore {

    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();

    public Optional<String> get(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public void put(UUID playerId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.put(playerId, sessionId);
        }
    }

    public void clear(UUID playerId) {
        sessions.remove(playerId);
    }

    public void clearAll() {
        sessions.clear();
    }
}
