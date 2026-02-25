package ru.fatedonate.minecraft.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ru.fatedonate.minecraft.api.ApiResult;
import ru.fatedonate.minecraft.api.GameApiClient;
import ru.fatedonate.minecraft.model.PlayerIdentity;
import ru.fatedonate.minecraft.model.TopupWatchSession;

public final class TopupWatchService {
    private final Map<String, TopupWatchSession> sessions = new ConcurrentHashMap<>();

    public int size() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }

    public void register(PlayerIdentity identity, String sessionId, long nowMillis, int timeoutSeconds) {
        Objects.requireNonNull(identity, "identity");
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        final long safeTimeoutSeconds = Math.max(1L, timeoutSeconds);
        sessions.put(sessionId, new TopupWatchSession(
                sessionId,
                identity.uuid(),
                identity.playerId(),
                identity.playerName(),
                nowMillis,
                nowMillis + safeTimeoutSeconds * 1000L
        ));
    }

    public List<PollEvent> poll(GameApiClient apiClient, String fallbackCurrency, long nowMillis) {
        Objects.requireNonNull(apiClient, "apiClient");
        if (sessions.isEmpty()) {
            return List.of();
        }

        final String defaultCurrency = fallbackCurrency == null || fallbackCurrency.isBlank()
                ? "RUB"
                : fallbackCurrency.trim();
        final List<PollEvent> events = new ArrayList<>();
        final List<TopupWatchSession> snapshot = new ArrayList<>(sessions.values());

        for (var watch : snapshot) {
            final boolean isExpiredByTimeout = watch.expiresAtMillis() <= nowMillis;
            final ApiResult<GameApiClient.TopupSessionResponse> statusResult = apiClient.getTopupSession(watch.sessionId());

            if (!statusResult.isSuccess() || statusResult.data() == null) {
                if (statusResult.statusCode() == 404) {
                    sessions.remove(watch.sessionId());
                    if (isExpiredByTimeout) {
                        events.add(new PollEvent.Expired(watch.playerUuid(), watch.sessionId()));
                    }
                } else if (isExpiredByTimeout) {
                    sessions.remove(watch.sessionId());
                    events.add(new PollEvent.Expired(watch.playerUuid(), watch.sessionId()));
                }
                continue;
            }

            final GameApiClient.TopupSessionResponse topupStatus = statusResult.data();
            final String status = topupStatus.status().toUpperCase(Locale.ROOT);

            if ("COMPLETED".equals(status)) {
                sessions.remove(watch.sessionId());
                BigDecimal balanceAfter = null;

                final ApiResult<GameApiClient.BalanceResponse> balanceResult = apiClient.getBalance(watch.playerId());
                if (balanceResult.isSuccess() && balanceResult.data() != null) {
                    balanceAfter = balanceResult.data().balance();
                }

                final BigDecimal amount = topupStatus.amount() == null ? BigDecimal.ZERO : topupStatus.amount();
                final String currency = topupStatus.currency() == null || topupStatus.currency().isBlank()
                        ? defaultCurrency
                        : topupStatus.currency();
                events.add(new PollEvent.Completed(
                        watch.playerUuid(),
                        watch.sessionId(),
                        watch.playerId(),
                        amount,
                        currency,
                        balanceAfter
                ));
                continue;
            }

            if (isExpiredByTimeout && "PENDING".equals(status)) {
                sessions.remove(watch.sessionId());
                events.add(new PollEvent.Expired(watch.playerUuid(), watch.sessionId()));
                continue;
            }

            if ("FAILED".equals(status)) {
                sessions.remove(watch.sessionId());
                events.add(new PollEvent.Failed(watch.playerUuid(), watch.sessionId()));
            }
        }

        return events;
    }

    public sealed interface PollEvent permits PollEvent.Completed, PollEvent.Expired, PollEvent.Failed {
        UUID playerUuid();

        String sessionId();

        record Completed(
                UUID playerUuid,
                String sessionId,
                String playerId,
                BigDecimal amount,
                String currency,
                BigDecimal balanceAfter
        ) implements PollEvent {
        }

        record Expired(UUID playerUuid, String sessionId) implements PollEvent {
        }

        record Failed(UUID playerUuid, String sessionId) implements PollEvent {
        }
    }
}
