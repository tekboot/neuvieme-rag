package com.ai.deepcode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for RAG-augmented chat.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagChatRequest(
        String message,
        List<UUID> projectIds,
        String model,
        String embedModel,
        Integer topK,
        String strategy, // "use_existing" | "reindex"
        String mode, // "all" | "selected"
        List<RagFileRef> files,
        Integer chunkSize,
        Integer chunkOverlap) {
    public RagChatRequest {
        if (topK == null || topK <= 0) {
            topK = 5;
        }
        if (strategy == null) {
            strategy = "reindex";
        }
        if (mode == null) {
            mode = "all";
        }
        if (chunkSize == null) {
            chunkSize = 1000;
        }
        if (chunkOverlap == null) {
            chunkOverlap = 200;
        }
    }
}
