package com.ai.deepcode.dto;

import java.util.UUID;

/**
 * Result of a vector similarity search.
 * Decouples the database/entity layer from the search results.
 */
public record ChunkHitDto(
        UUID id,
        UUID projectId,
        String filePath,
        int chunkIndex,
        String content,
        double score) {
}
