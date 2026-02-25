package ru.fatedonate.minecraft.model;

import java.math.BigDecimal;

public record BalanceCacheEntry(BigDecimal balance, long expiresAtMillis) {
}
