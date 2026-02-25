package ru.fatedonate.minecraft.api;

public final class ApiResult<T> {
    private final boolean success;
    private final int statusCode;
    private final String error;
    private final T data;

    private ApiResult(boolean success, int statusCode, String error, T data) {
        this.success = success;
        this.statusCode = statusCode;
        this.error = error;
        this.data = data;
    }

    public static <T> ApiResult<T> success(int statusCode, T data) {
        return new ApiResult<>(true, statusCode, null, data);
    }

    public static <T> ApiResult<T> fail(int statusCode, String error) {
        return new ApiResult<>(false, statusCode, error, null);
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

    public T data() {
        return data;
    }
}
