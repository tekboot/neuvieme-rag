package com.ai.deepcode.dto;

public record ErrorResponse(
        int status,
        String code,
        String message
) {
}
