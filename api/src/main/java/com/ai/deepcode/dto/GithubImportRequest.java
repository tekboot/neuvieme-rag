package com.ai.deepcode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request DTO for GitHub import.
 *
 * @param owner      GitHub owner (user or organization)
 * @param repo       Repository name
 * @param branch     Branch name (optional, defaults to HEAD/main)
 * @param subPath    Sub-path within repo to import (optional)
 * @param indexMode  Import mode: "PREINDEX" to index immediately, "LAZY" to index later (default)
 * @param embedModel Embedding model to use for indexing (optional, defaults to "nomic-embed-text")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubImportRequest(
        String owner,
        String repo,
        String branch,
        String subPath,
        String indexMode,
        String embedModel
) {
    /**
     * Check if pre-indexing is requested.
     */
    public boolean shouldPreIndex() {
        return "PREINDEX".equalsIgnoreCase(indexMode);
    }

    /**
     * Get the embedding model, defaulting to "nomic-embed-text" if not specified.
     */
    public String getEmbedModelOrDefault() {
        return (embedModel == null || embedModel.isBlank()) ? "nomic-embed-text" : embedModel;
    }
}
