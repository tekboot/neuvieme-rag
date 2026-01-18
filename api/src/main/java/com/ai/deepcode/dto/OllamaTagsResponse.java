package com.ai.deepcode.dto;

import java.util.List;
import java.util.Map;

/**
 * Response from Ollama GET /api/tags endpoint
 */
public record OllamaTagsResponse(
        List<OllamaModelDto> models) {
}
