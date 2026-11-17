package com.fasttrack.common;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape every endpoint returns.
 *
 * @param code        stable machine-readable code, e.g. {@code not_found}
 * @param message     human-readable message, safe to show to the user
 * @param fieldErrors per-field validation failures, empty when not applicable
 * @param timestamp   when the error was produced
 */
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        Instant timestamp) {

    public record FieldError(String field, String message) {}

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of(), Instant.now());
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(code, message, fieldErrors, Instant.now());
    }
}
