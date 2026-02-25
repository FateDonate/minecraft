package ru.fatedonate.minecraft.api;

public final class ApiResult<T> {
    private final boolean success;
    private final int statusCode;
    private final String error;
    private final Integer retryAfterSeconds;
    private final T data;

    private ApiResult(boolean success, int statusCode, String error, Integer retryAfterSeconds, T data) {
        this.success = success;
        this.statusCode = statusCode;
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
        this.data = data;
    }

    public static <T> ApiResult<T> success(int statusCode, T data) {
        return new ApiResult<>(true, statusCode, null, null, data);
    }

    public static <T> ApiResult<T> fail(int statusCode, String error) {
        return new ApiResult<>(false, statusCode, error, null, null);
    }

    public static <T> ApiResult<T> fail(int statusCode, String error, Integer retryAfterSeconds) {
        return new ApiResult<>(false, statusCode, error, retryAfterSeconds, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public int statusCode() {
        return statusCode;
    }

    public String error() {
        return error;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public T data() {
        return data;
    }
}
