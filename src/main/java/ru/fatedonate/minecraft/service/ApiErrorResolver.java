package ru.fatedonate.minecraft.service;

import ru.fatedonate.minecraft.api.ApiResult;

public final class ApiErrorResolver {
    private ApiErrorResolver() {
    }

    public static String resolve(ApiResult<?> apiResult, String fallbackMessage) {
        final int statusCode = apiResult.statusCode();

        if (statusCode == 401) {
            return "Неверный Private Key. Проверьте settings.private-key.";
        }

        if (statusCode == 404) {
            return "Сервер или сущность не найдены в API. Проверьте settings.server-id.";
        }

        if (statusCode == 429) {
            final Integer retryAfter = apiResult.retryAfterSeconds();
            if (retryAfter != null && retryAfter > 0) {
                return "Лимит запросов к API превышен. Повторите через " + retryAfter + " сек.";
            }
            return "Лимит запросов к API превышен. Попробуйте позже.";
        }

        if (apiResult.error() != null && !apiResult.error().isBlank()) {
            return apiResult.error();
        }

        return fallbackMessage;
    }
}
