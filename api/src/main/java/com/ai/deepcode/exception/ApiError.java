package com.ai.deepcode.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Standard error response structure for the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        Map<String, Object> details,
        String traceId) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(OffsetDateTime.now(), status, error, null, message, null, null, null);
    }

    public static ApiError of(int status, String error, String code, String message, Map<String, Object> details) {
        return new ApiError(OffsetDateTime.now(), status, error, code, message, null, details, null);
    }
}
