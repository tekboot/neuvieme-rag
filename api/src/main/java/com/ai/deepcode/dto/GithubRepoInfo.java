package com.ai.deepcode.dto;

/**
 * DTO for GitHub repository info returned from list repos endpoint.
 */
public record GithubRepoInfo(
        String name,
        String fullName,
        String defaultBranch,
        boolean isPrivate,
        String description,
        String updatedAt,
        int stargazersCount,
        String language
) {
    /**
     * Create from GitHub API response map.
     */
    @SuppressWarnings("unchecked")
    public static GithubRepoInfo fromApiResponse(java.util.Map<String, Object> map) {
        return new GithubRepoInfo(
                (String) map.get("name"),
                (String) map.get("full_name"),
                (String) map.getOrDefault("default_branch", "main"),
                Boolean.TRUE.equals(map.get("private")),
                (String) map.get("description"),
                (String) map.get("updated_at"),
                map.get("stargazers_count") != null ? ((Number) map.get("stargazers_count")).intValue() : 0,
                (String) map.get("language")
        );
    }
}
