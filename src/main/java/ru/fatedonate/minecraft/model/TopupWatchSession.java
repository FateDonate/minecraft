package ru.fatedonate.minecraft.model;

import java.util.UUID;

public record TopupWatchSession(
        String sessionId,
        UUID playerUuid,
        String playerId,
        String playerName,
        long createdAtMillis,
        long expiresAtMillis
) {
}
