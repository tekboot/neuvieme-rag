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
        int failedFiles,
        int totalChunks,
        int progress,
        String message,
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

        int totalFiles = entity.getTotalFiles() != null ? entity.getTotalFiles() : 0;
        int indexedFiles = entity.getIndexedFiles() != null ? entity.getIndexedFiles() : 0;
        int failedFiles = entity.getFailedFiles() != null ? entity.getFailedFiles() : 0;
        int totalChunks = entity.getTotalChunks() != null ? entity.getTotalChunks() : 0;

        // Calculate progress percentage (0-100)
        int progress = 0;
        if (totalFiles > 0) {
            progress = (int) (((indexedFiles + failedFiles) * 100.0) / totalFiles);
        }

        // Generate human-readable message based on status
        String message = switch (entity.getStatus()) {
            case PENDING -> "Waiting to start...";
            case IN_PROGRESS -> String.format("Indexing files... (%d/%d)", indexedFiles, totalFiles);
            case COMPLETED -> String.format("Completed! %d files indexed, %d chunks created.", indexedFiles, totalChunks);
            case COMPLETED_WITH_ERRORS -> String.format("Completed with errors. %d/%d files indexed.", indexedFiles, totalFiles);
            case FAILED -> entity.getErrorMessage() != null ? entity.getErrorMessage() : "Indexing failed.";
        };

        return new IndexStatusResponse(
                entity.getProject().getId(),
                entity.getStatus().name(),
                totalFiles,
                indexedFiles,
                failedFiles,
                totalChunks,
                progress,
                message,
                entity.getEmbedModel(),
                entity.getChunkSize(),
                entity.getChunkOverlap(),
                entity.getErrorMessage(),
                entity.getStartedAt(),
                entity.getCompletedAt());
    }
}
