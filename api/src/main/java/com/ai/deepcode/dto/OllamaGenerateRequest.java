package com.ai.deepcode.dto;

public record OllamaGenerateRequest(
        String model,
        String prompt,
        boolean stream
) {}
