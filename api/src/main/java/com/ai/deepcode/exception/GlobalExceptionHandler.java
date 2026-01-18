package com.ai.deepcode.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OllamaUnavailableException.class)
    public ResponseEntity<ApiError> handleOllamaUnavailable(OllamaUnavailableException e, WebRequest request) {
        log.error("[ExceptionHandler] Ollama unavailable: {}", e.getMessage());

        ApiError error = new ApiError(
                OffsetDateTime.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "OLLAMA_UNAVAILABLE",
                "Local Ollama service is down or embeddings endpoint is not available",
                getRequestPath(request),
                Map.of(
                        "url", "http://localhost:11434/api/embeddings",
                        "hint", "Start 'ollama serve' / restart Ollama service",
                        "originalError", e.getMessage()),
                null);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException e, WebRequest request) {
        log.error("[ExceptionHandler] ResponseStatusException: status={}, reason={}", e.getStatusCode(), e.getReason());

        String code = e.getReason();
        if (code != null && code.contains(":")) {
            code = code.split(":")[0].trim();
        }

        ApiError error = new ApiError(
                OffsetDateTime.now(),
                e.getStatusCode().value(),
                HttpStatus.valueOf(e.getStatusCode().value()).getReasonPhrase(),
                (code != null && !code.isBlank()) ? code : "API_ERROR",
                e.getReason() != null ? e.getReason() : e.getMessage(),
                getRequestPath(request),
                null,
                null);

        return ResponseEntity.status(e.getStatusCode()).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException e, WebRequest request) {
        log.error("[ExceptionHandler] HttpMessageNotReadableException: {}", e.getMessage());

        ApiError error = new ApiError(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "INVALID_PAYLOAD",
                "Invalid request payload. Please refresh the page.",
                getRequestPath(request),
                Map.of("details", e.getMostSpecificCause().getMessage()),
                null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e, WebRequest request) {
        log.error("[ExceptionHandler] Unexpected error: ", e);

        ApiError error = new ApiError(
                OffsetDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred: " + e.getMessage(),
                getRequestPath(request),
                null,
                null);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String getRequestPath(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            return ((ServletWebRequest) request).getRequest().getRequestURI();
        }
        return null;
    }
}
