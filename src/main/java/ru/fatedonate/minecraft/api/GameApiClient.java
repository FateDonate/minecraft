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
        final var request = baseRequest(buildUrl("balance/" + playerId))
                .GET()
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error());
        }

        final var json = response.data();
        final JsonElement balanceElement = json.get("balance");
        if (balanceElement == null || !balanceElement.isJsonPrimitive()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API баланса."
            );
        }

        try {
            return ApiResult.success(
                    response.statusCode(),
                    new BalanceResponse(balanceElement.getAsBigDecimal().stripTrailingZeros())
            );
        } catch (NumberFormatException exception) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API баланса."
            );
        }
    }

    public ApiResult<TopupLinkResponse> createTopupLink(String playerId, BigDecimal amount) {
        final var payload = new JsonObject();
        payload.addProperty("steamId64", playerId);
        payload.addProperty("amount", amount);
        payload.addProperty("currency", settings.currency());

        final var request = baseRequest(buildUrl("topup-link"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error());
        }

        final var json = response.data();
        final JsonElement checkoutUrlElement = json.get("checkoutUrl");
        if (checkoutUrlElement == null || !checkoutUrlElement.isJsonPrimitive()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось получить ссылку на пополнение."
            );
        }

        final String checkoutUrl = checkoutUrlElement.getAsString().trim();
        if (checkoutUrl.isEmpty()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось получить ссылку на пополнение."
            );
        }

        return ApiResult.success(
                response.statusCode(),
                new TopupLinkResponse(checkoutUrl)
        );
    }

    public ApiResult<DebitResponse> debit(String playerId, BigDecimal amount, String description) {
        final var payload = new JsonObject();
        payload.addProperty("steamId64", playerId);
        payload.addProperty("amount", amount);
        payload.addProperty("description", description);

        final var request = baseRequest(buildUrl("debit"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        final var response = send(request);
        if (!response.isSuccess() || response.data() == null) {
            return ApiResult.fail(response.statusCode(), response.error());
        }

        final var json = response.data();
        final JsonElement balanceElement = json.get("balance");
        if (balanceElement == null || !balanceElement.isJsonPrimitive()) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API списания."
            );
        }

        try {
            return ApiResult.success(
                    response.statusCode(),
                    new DebitResponse(balanceElement.getAsBigDecimal().stripTrailingZeros())
            );
        } catch (NumberFormatException exception) {
            return ApiResult.fail(
                    response.statusCode(),
                    "Не удалось разобрать ответ API списания."
            );
        }
    }

    private HttpRequest.Builder baseRequest(String url) {
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

            if (statusCode >= 200 && statusCode < 300) {
                final JsonObject payload = parseJsonObject(body);
                if (payload == null) {
                    return ApiResult.fail(statusCode, "Не удалось разобрать ответ API.");
                }
                return ApiResult.success(statusCode, payload);
            }

            return ApiResult.fail(statusCode, parseError(body, statusCode));
        } catch (Exception exception) {
            return ApiResult.fail(
                    0,
                    "Ошибка соединения с API: " + exception.getMessage()
            );
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
        final String messageText = payload.has("message") && payload.get("message").isJsonPrimitive()
                ? payload.get("message").getAsString().trim()
                : "";

        if (!errorText.isEmpty() && !messageText.isEmpty()) {
            return errorText + ": " + messageText;
        }
        if (!errorText.isEmpty()) {
            return errorText;
        }
        if (!messageText.isEmpty()) {
            return messageText;
        }

        return "HTTP " + statusCode;
    }

    private String buildUrl(String path) {
        final String base = settings.apiBaseUrl().trim().replaceAll("/+$", "");
        final String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return base + "/game/" + settings.serverId().trim() + "/" + normalizedPath;
    }

    public record BalanceResponse(BigDecimal balance) {
    }

    public record TopupLinkResponse(String checkoutUrl) {
    }

    public record DebitResponse(BigDecimal balance) {
    }
}
