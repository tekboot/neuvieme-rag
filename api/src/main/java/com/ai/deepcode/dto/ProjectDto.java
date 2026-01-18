package com.ai.deepcode.dto;

import com.ai.deepcode.entity.Project;

import java.util.UUID;

/**
 * DTO for Project entity to avoid exposing JPA internals in API responses.
 */
public record ProjectDto(
        UUID id,
        String name,
        String displayName,
        String source,
        String githubOwner,
        String githubRepo,
        String githubBranch,
        Integer fileCount,
        String createdAt,
        String updatedAt
) {
    /**
     * Convert a Project entity to ProjectDto.
     */
    public static ProjectDto from(Project project) {
        return new ProjectDto(
                project.getId(),
                project.getName(),
                project.getDisplayName(),
                project.getSource(),
                project.getGithubOwner(),
                project.getGithubRepo(),
                project.getGithubBranch(),
                project.getFileCount(),
                project.getCreatedAt() != null ? project.getCreatedAt().toString() : null,
                project.getUpdatedAt() != null ? project.getUpdatedAt().toString() : null
        );
    }
}
