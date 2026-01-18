package com.ai.deepcode.dto;

/**
 * Request body for Ollama POST /api/pull
 */
public record OllamaPullRequest(
        String name,
        boolean stream) {
}
