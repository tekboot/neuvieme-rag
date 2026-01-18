package com.ai.deepcode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for indexing a project.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexRequest(
        UUID projectId,
        Map<String, String> fileContents,
        List<String> filePaths,
        String source,
        String mode,
        List<RagFileRef> files,
        String embedModel,
        Integer chunkSize,
        Integer chunkOverlap) {
    public IndexRequest {
        if (embedModel == null || embedModel.isBlank()) {
            embedModel = "nomic-embed-text";
        }
        if (chunkSize == null || chunkSize <= 0) {
            chunkSize = 500;
        }
        if (chunkOverlap == null || chunkOverlap < 0) {
            chunkOverlap = 50;
        }
        if (source == null || source.isBlank()) {
            source = "device";
        }
    }
}
