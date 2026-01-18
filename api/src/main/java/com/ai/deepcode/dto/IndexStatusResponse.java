package com.ai.deepcode.dto;

import com.ai.deepcode.entity.IndexStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO for index status.
 */
public record IndexStatusResponse(
        UUID projectId,
        String status,
        int totalFiles,
        int indexedFiles,
        int totalChunks,
        String embedModel,
        Integer chunkSize,
        Integer chunkOverlap,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt) {
    public static IndexStatusResponse from(IndexStatus entity) {
        if (entity == null) {
            return null;
        }
        return new IndexStatusResponse(
                entity.getProject().getId(),
                entity.getStatus().name(),
                entity.getTotalFiles() != null ? entity.getTotalFiles() : 0,
                entity.getIndexedFiles() != null ? entity.getIndexedFiles() : 0,
                entity.getTotalChunks() != null ? entity.getTotalChunks() : 0,
                entity.getEmbedModel(),
                entity.getChunkSize(),
                entity.getChunkOverlap(),
                entity.getErrorMessage(),
                entity.getStartedAt(),
                entity.getCompletedAt());
    }
}
