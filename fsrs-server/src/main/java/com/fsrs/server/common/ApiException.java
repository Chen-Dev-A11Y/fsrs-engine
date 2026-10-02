package com.fsrs.server.common;

/**
 * A business-rule failure that maps to a specific HTTP status.
 *
 * <p>Using one exception type with a status keeps the controllers free of
 * try/catch blocks and guarantees a single, consistent error body.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "UNAUTHORIZED", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, "TOO_MANY_REQUESTS", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }
}
