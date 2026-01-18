package com.ai.deepcode.dto;

/**
 * Individual model info from Ollama
 */
public record OllamaModelDto(
        String name,
        Long size,
        String modified_at,
        String digest) {
}
