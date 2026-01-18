package com.ai.deepcode.dto;

/**
 * DTO for model pull operation result.
 */
public record PullResult(
    boolean success,
    String message,
    String error
) {}
