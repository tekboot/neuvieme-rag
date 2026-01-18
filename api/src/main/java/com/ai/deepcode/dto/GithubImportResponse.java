package com.ai.deepcode.dto;

import com.ai.deepcode.entity.Project;
import java.util.List;

/**
 * Response DTO for GitHub import.
 * Uses ProjectDto instead of entity to avoid JPA serialization issues.
 */
public record GithubImportResponse(
        ProjectDto project,
        List<FileNode> tree) {

    /**
     * Factory method to create response from entity.
     */
    public static GithubImportResponse from(Project project, List<FileNode> tree) {
        return new GithubImportResponse(ProjectDto.from(project), tree);
    }
}
