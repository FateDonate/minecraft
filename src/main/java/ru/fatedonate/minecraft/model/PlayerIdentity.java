package ru.fatedonate.minecraft.model;

import java.util.UUID;

public record PlayerIdentity(UUID uuid, String playerName, String playerId) {
}
