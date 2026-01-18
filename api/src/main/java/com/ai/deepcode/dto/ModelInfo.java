package com.ai.deepcode.dto;

/**
 * DTO for Ollama model information.
 */
public record ModelInfo(
    String name,
    String size,
    String modifiedAt,
    String status,
    boolean active
) {}
