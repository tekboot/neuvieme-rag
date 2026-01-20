package com.ai.deepcode.api;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class GithubApiClient {

    private final RestTemplate rest = new RestTemplate();

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("Accept", "application/vnd.github+json");
        h.set("X-GitHub-Api-Version", "2022-11-28");
        return h;
    }

    public List<Map<String, Object>> listRepos(String token) {
        String url = "https://api.github.com/user/repos?per_page=100&sort=updated";
        ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers(token)), List.class);
        return (List<Map<String, Object>>) resp.getBody();
    }

    /**
     * List repositories for a specific owner (user or organization).
     * For users: GET /users/{owner}/repos
     * For orgs: GET /orgs/{owner}/repos
     * We try user first, then org if user fails.
     */
    public List<Map<String, Object>> listReposByOwner(String token, String owner) {
        // Try user repos first
        try {
            String url = "https://api.github.com/users/%s/repos?per_page=100&sort=updated".formatted(owner);
            ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers(token)), List.class);
            return (List<Map<String, Object>>) resp.getBody();
        } catch (Exception e) {
            // Try org repos
            String url = "https://api.github.com/orgs/%s/repos?per_page=100&sort=updated".formatted(owner);
            ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers(token)), List.class);
            return (List<Map<String, Object>>) resp.getBody();
        }
    }

    /**
     * Get repository details including default branch.
     */
    public Map<String, Object> getRepo(String token, String owner, String repo) {
        String url = "https://api.github.com/repos/%s/%s".formatted(owner, repo);
        ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers(token)), Map.class);
        return (Map<String, Object>) resp.getBody();
    }

    // Get file content (base64) + sha
    public Map<String, Object> getFile(String token, String owner, String repo, String path, String ref) {
        String url = "https://api.github.com/repos/%s/%s/contents/%s%s".formatted(
                owner, repo, path, (ref == null || ref.isBlank()) ? "" : "?ref=" + ref
        );
        ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers(token)), Map.class);
        return (Map<String, Object>) resp.getBody();
    }

    // Simple update file API (creates a commit) - easiest route
    public Map<String, Object> updateFile(
            String token,
            String owner,
            String repo,
            String path,
            String branch,
            String message,
            String newContentUtf8,
            String existingFileSha // required for updating existing files
    ) {
        String url = "https://api.github.com/repos/%s/%s/contents/%s".formatted(owner, repo, path);

        String contentB64 = Base64.getEncoder().encodeToString(newContentUtf8.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("content", contentB64);
        body.put("branch", branch);
        if (existingFileSha != null && !existingFileSha.isBlank()) {
            body.put("sha", existingFileSha);
        }

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers(token));
        ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.PUT, req, Map.class);
        return (Map<String, Object>) resp.getBody();
    }

    public Map<String, Object> getRepoTree(String token, String owner, String repo, String branch) {
        String ref = (branch == null || branch.isBlank()) ? "HEAD" : branch;
        String url = "https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1".formatted(owner, repo, ref);

        ResponseEntity<Map> resp = rest.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers(token)),
                Map.class
        );

        return (Map<String, Object>) resp.getBody();
    }

    /**
     * List branches for a repository.
     * GET /repos/{owner}/{repo}/branches
     */
    public List<Map<String, Object>> listBranches(String token, String owner, String repo) {
        String url = "https://api.github.com/repos/%s/%s/branches?per_page=100".formatted(owner, repo);
        ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers(token)), List.class);
        return (List<Map<String, Object>>) resp.getBody();
    }

}
