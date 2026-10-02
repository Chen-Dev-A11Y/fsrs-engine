package com.fsrs.server.common;

import java.util.Map;

/**
 * The single error body shape used by {@link GlobalExceptionHandler}.
 *
 * @param code    stable machine-readable code, e.g. {@code NOT_FOUND}
 * @param message message meant to be shown to a developer, not to an end user
 * @param details optional field-level information, mainly for validation failures
 */
public record ErrorResponse(String code, String message, Map<String, String> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
