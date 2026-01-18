package com.ai.deepcode.dto;

/**
 * Response from Ollama POST /api/pull
 */
public record OllamaPullResponse(
        String status,
        String error) {
}
