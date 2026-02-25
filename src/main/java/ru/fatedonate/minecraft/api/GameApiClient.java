package ru.fatedonate.minecraft.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import ru.fatedonate.minecraft.config.AppConfig;

public final class GameApiClient {
    private final AppConfig.Settings settings;
    private final HttpClient httpClient;

    public GameApiClient(AppConfig.Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .build();
    }

    public ApiResult<BalanceResponse> getBalance(String playerId) {
        final var request = privateRequest(buildUrl("balance/" + playerId))
                .GET()
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error(), response.retryAfterSeconds());
        }

        final BigDecimal balance = readBigDecimal(response.data(), "balance");
        if (balance == null) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API баланса.",
                    response.retryAfterSeconds()
            );
        }

        return ApiResult.success(response.statusCode(), new BalanceResponse(balance));
    }

    public ApiResult<TopupLinkResponse> createTopupLink(String playerId, String playerName, BigDecimal amount) {
        final var payload = new JsonObject();
        payload.addProperty("playerId", playerId);
        payload.addProperty("playerName", playerName);
        payload.addProperty("amount", amount);
        payload.addProperty("currency", settings.currency());

        final var request = privateRequest(buildUrl("topup-link"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error(), response.retryAfterSeconds());
        }

        final var json = response.data();
        final String sessionId = readString(json, "sessionId");
        final String checkoutUrl = readString(json, "checkoutUrl");

        if (sessionId == null || sessionId.isBlank() || checkoutUrl == null || checkoutUrl.isBlank()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось получить ссылку на пополнение.",
                    response.retryAfterSeconds()
            );
        }

        return ApiResult.success(
                response.statusCode(),
                new TopupLinkResponse(sessionId, checkoutUrl.trim())
        );
    }

    public ApiResult<PurchaseResponse> createPurchase(
            String playerId,
            String playerName,
            String itemId,
            String itemName,
            BigDecimal amount,
            String description
    ) {
        final var payload = new JsonObject();
        payload.addProperty("playerId", playerId);
        payload.addProperty("playerName", playerName);
        payload.addProperty("itemId", itemId);
        payload.addProperty("itemName", itemName);
        payload.addProperty("amount", amount);
        payload.addProperty("currency", settings.currency());
        payload.addProperty("description", description);

        final var request = privateRequest(buildUrl("purchase"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error(), response.retryAfterSeconds());
        }

        final var json = response.data();
        final BigDecimal balance = readBigDecimal(json, "balance");

        if (balance == null) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API покупки.",
                    response.retryAfterSeconds()
            );
        }

        return ApiResult.success(
                response.statusCode(),
                new PurchaseResponse(balance)
        );
    }

    public ApiResult<TopupSessionResponse> getTopupSession(String sessionId) {
        final var request = privateRequest(buildUrl("topup-session/" + sessionId))
                .GET()
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error(), response.retryAfterSeconds());
        }

        final JsonObject root = response.data();
        if (!root.has("session") || !root.get("session").isJsonObject()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API статуса пополнения.",
                    response.retryAfterSeconds()
            );
        }

        final JsonObject session = root.getAsJsonObject("session");
        final String responseSessionId = readString(session, "id");
        final String status = readString(session, "status");
        final String playerId = readString(session, "playerId");
        final String currency = readString(session, "currency");
        final BigDecimal amount = readBigDecimal(session, "amount");

        if (responseSessionId == null || responseSessionId.isBlank() || status == null || status.isBlank()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API статуса пополнения.",
                    response.retryAfterSeconds()
            );
        }

        return ApiResult.success(
                response.statusCode(),
                new TopupSessionResponse(
                        responseSessionId,
                        status,
                        playerId == null ? "" : playerId,
                        amount == null ? BigDecimal.ZERO : amount,
                        currency == null || currency.isBlank() ? settings.currency() : currency
                )
        );
    }

    private HttpRequest.Builder privateRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("x-private-key", settings.privateKey().trim());
    }

    private ApiResult<JsonObject> send(HttpRequest request) {
        try {
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final int statusCode = response.statusCode();
            final String body = response.body() == null ? "" : response.body();
            final Integer retryAfterSeconds = parseRetryAfter(response);

            if (statusCode >= 200 && statusCode < 300) {
                final JsonObject payload = parseJsonObject(body);
                if (payload == null) {
                    return ApiResult.fail(statusCode, "Не удалось разобрать ответ API.", retryAfterSeconds);
                }
                return ApiResult.success(statusCode, payload);
            }

            return ApiResult.fail(statusCode, parseError(body, statusCode), retryAfterSeconds);
        } catch (Exception exception) {
            return ApiResult.fail(
                    0,
                    "Ошибка соединения с API: " + exception.getMessage(),
                    null
            );
        }
    }

    private static Integer parseRetryAfter(HttpResponse<?> response) {
        final String value = response.headers().firstValue("retry-after").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            final int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static JsonObject parseJsonObject(String body) {
        try {
            final JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String parseError(String body, int statusCode) {
        final JsonObject payload = parseJsonObject(body);
        if (payload == null) {
            return "HTTP " + statusCode;
        }

        final String errorText = payload.has("error") && payload.get("error").isJsonPrimitive()
                ? payload.get("error").getAsString().trim()
                : "";
        if (!errorText.isEmpty()) {
            return errorText;
        }

        return "HTTP " + statusCode;
    }

    private static String readString(JsonObject json, String key) {
        if (json == null || !json.has(key)) {
            return null;
        }

        final JsonElement value = json.get(key);
        if (!value.isJsonPrimitive()) {
            return null;
        }

        final String stringValue = value.getAsString();
        return stringValue == null ? null : stringValue.trim();
    }

    private static BigDecimal readBigDecimal(JsonObject json, String key) {
        if (json == null || !json.has(key)) {
            return null;
        }

        final JsonElement value = json.get(key);
        if (!value.isJsonPrimitive()) {
            return null;
        }

        try {
            return value.getAsBigDecimal().stripTrailingZeros();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String buildUrl(String path) {
        final String base = settings.apiBaseUrl().trim().replaceAll("/+$", "");
        final String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return base + "/game/" + settings.serverId().trim() + "/" + normalizedPath;
    }

    public record BalanceResponse(BigDecimal balance) {
    }

    public record TopupLinkResponse(String sessionId, String checkoutUrl) {
    }

    public record PurchaseResponse(BigDecimal balance) {
    }

    public record TopupSessionResponse(String sessionId, String status, String playerId, BigDecimal amount, String currency) {
    }
}
