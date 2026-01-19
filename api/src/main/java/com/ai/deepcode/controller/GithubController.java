package com.ai.deepcode.controller;

import com.ai.deepcode.api.GithubApiClient;
import com.ai.deepcode.dto.FileNode;
import com.ai.deepcode.dto.GithubImportRequest;
import com.ai.deepcode.dto.UpdateFileRequest;
import com.ai.deepcode.service.GithubTokenService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/github")
public class GithubController {

    private static final Logger log = LoggerFactory.getLogger(GithubController.class);

    private final GithubTokenService tokenService;
    private final GithubApiClient github;
    private final com.ai.deepcode.repository.ProjectRepository projectRepository;

    public GithubController(GithubTokenService tokenService, GithubApiClient github,
            com.ai.deepcode.repository.ProjectRepository projectRepository) {
        this.tokenService = tokenService;
        this.github = github;
        this.projectRepository = projectRepository;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        if (auth == null) {
            return ResponseEntity.ok(Map.of(
                    "authorized", false,
                    "message", "Not authenticated with GitHub"
            ));
        }

        try {
            String token = tokenService.getAccessToken(auth);
            boolean ok = (token != null && !token.isBlank());
            return ResponseEntity.ok(Map.of(
                    "authorized", ok,
                    "message", ok ? "GitHub token available" : "GitHub token missing"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "authorized", false,
                    "message", "GitHub token retrieval failed: " + e.getMessage()
            ));
        }
    }


    @PostMapping("/import")
    public ResponseEntity<?> importRepo(Authentication auth, @RequestBody GithubImportRequest req) {
        log.info("[GithubImport] START owner={} repo={} branch={} subPath={}",
                req.owner(), req.repo(), req.branch(), req.subPath());

        // Validate authentication
        if (auth == null) {
            log.error("[GithubImport] FAIL: No authentication provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_AUTH_MISSING", "message", "GitHub authentication required. Please connect your GitHub account."));
        }

        String token;
        try {
            token = tokenService.getAccessToken(auth);
            log.info("[GithubImport] Auth OK, token length={}", token != null ? token.length() : 0);
        } catch (Exception e) {
            log.error("[GithubImport] FAIL: Could not get access token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_ERROR", "message", "Failed to get GitHub token: " + e.getMessage()));
        }

        if (token == null || token.isBlank()) {
            log.error("[GithubImport] FAIL: Token is null or blank");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_MISSING", "message", "GitHub token is missing. Please reconnect your GitHub account."));
        }

        String owner = req.owner();
        String repo = req.repo();
        String branch = (req.branch() == null || req.branch().isBlank()) ? "HEAD" : req.branch();
        String subPath = normalize(req.subPath());

        try {
            // Persist Project
            log.info("[GithubImport] Looking up or creating project for {}/{} branch={}", owner, repo, branch);
            com.ai.deepcode.entity.Project project = projectRepository
                    .findByGithubOwnerAndGithubRepoAndGithubBranch(owner, repo, branch)
                    .orElseGet(() -> {
                        log.info("[GithubImport] Creating new project record");
                        com.ai.deepcode.entity.Project p = new com.ai.deepcode.entity.Project();
                        p.setId(UUID.randomUUID());
                        p.setName(repo);
                        p.setDisplayName(owner + "/" + repo);
                        p.setSource("github");
                        p.setGithubOwner(owner);
                        p.setGithubRepo(repo);
                        p.setGithubBranch(branch);
                        return projectRepository.save(p);
                    });

            log.info("[GithubImport] Project ID={}, fetching repo tree from GitHub API", project.getId());

            Map<String, Object> treeResponse = github.getRepoTree(token, owner, repo, branch);

            if (treeResponse == null) {
                log.error("[GithubImport] FAIL: GitHub API returned null tree response");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("code", "GITHUB_API_ERROR", "message", "GitHub API returned empty response"));
            }

            List<Map<String, Object>> items = (List<Map<String, Object>>) treeResponse.getOrDefault("tree", List.of());
            log.info("[GithubImport] GitHub API returned {} tree items", items.size());

            // Extract only blobs (files) and trees (folders)
            List<String> paths = items.stream()
                    .map(m -> (String) m.get("path"))
                    .filter(Objects::nonNull)
                    .filter(p -> subPath == null || p.startsWith(subPath))
                    .collect(Collectors.toList());

            // If subPath exists, remove its prefix so tree starts from that folder
            if (subPath != null) {
                paths = paths.stream()
                        .map(p -> p.substring(subPath.length()))
                        .map(p -> p.startsWith("/") ? p.substring(1) : p)
                        .filter(p -> !p.isBlank())
                        .toList();
            }

            log.info("[GithubImport] Building file tree from {} paths", paths.size());

            // Update file count
            List<FileNode> tree = buildTree(paths);
            project.setFileCount(paths.size());
            projectRepository.save(project);

            log.info("[GithubImport] SUCCESS projectId={} files={} treeRoots={}",
                    project.getId(), paths.size(), tree.size());

            return ResponseEntity.ok(com.ai.deepcode.dto.GithubImportResponse.from(project, tree));

        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            log.error("[GithubImport] FAIL: GitHub API returned 401 Unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_UNAUTHORIZED", "message", "GitHub token expired or invalid. Please reconnect your GitHub account."));
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            log.error("[GithubImport] FAIL: GitHub API returned 403 Forbidden");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "GITHUB_FORBIDDEN", "message", "Access denied to repository. Check repository permissions."));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.error("[GithubImport] FAIL: GitHub API returned 404 Not Found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "GITHUB_NOT_FOUND", "message", "Repository or branch not found: " + owner + "/" + repo + " branch=" + branch));
        } catch (Exception e) {
            log.error("[GithubImport] FAIL: Unexpected error: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "GITHUB_IMPORT_FAILED", "message", "Import failed: " + e.getMessage(), "details", e.getClass().getSimpleName()));
        }
    }

    // Existing endpoints unchanged
    @GetMapping("/repos")
    public List<Map<String, Object>> repos(Authentication auth) {
        String token = tokenService.getAccessToken(auth);
        return github.listRepos(token);
    }

    @GetMapping("/file")
    public Map<String, Object> getFile(
            Authentication auth,
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam String path,
            @RequestParam(required = false) String ref) {
        String token = tokenService.getAccessToken(auth);
        return github.getFile(token, owner, repo, path, ref);
    }

    @PostMapping("/file")
    public Map<String, Object> updateFile(Authentication auth, @RequestBody @Valid UpdateFileRequest req) {
        String token = tokenService.getAccessToken(auth);

        Map<String, Object> existing = github.getFile(token, req.owner(), req.repo(), req.path(), req.branch());
        String sha = (String) existing.get("sha");

        return github.updateFile(
                token,
                req.owner(),
                req.repo(),
                req.path(),
                req.branch(),
                req.commitMessage(),
                req.newContentUtf8(),
                sha);
    }

    // ---- helpers ----
    private static String normalize(String p) {
        if (p == null)
            return null;
        String x = p.trim().replace("\\", "/");
        while (x.startsWith("/"))
            x = x.substring(1);
        while (x.endsWith("/"))
            x = x.substring(0, x.length() - 1);
        return x.isBlank() ? null : x;
    }

    private static List<FileNode> buildTree(List<String> filePaths) {
        Node root = new Node("", "folder", "");

        for (String path : filePaths) {
            String[] parts = path.split("/");
            Node current = root;
            String currentPath = "";

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean isLast = (i == parts.length - 1);
                currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;

                String type = isLast ? "file" : "folder";
                Node next = current.children.get(part);
                if (next == null) {
                    next = new Node(part, type, currentPath);
                    current.children.put(part, next);
                }
                current = next;

                if (!isLast)
                    current.type = "folder";
            }
        }

        return root.toFileNodes();
    }

    private static class Node {
        String name;
        String type; // folder/file
        String path;
        Map<String, Node> children = new TreeMap<>();

        Node(String name, String type, String path) {
            this.name = name;
            this.type = type;
            this.path = path;
        }

        List<FileNode> toFileNodes() {
            return children.values().stream().map(n -> new FileNode(
                    UUID.randomUUID().toString(),
                    n.name,
                    n.type,
                    n.path,
                    n.type.equals("folder") ? n.toFileNodes() : null,
                    false)).toList();
        }
    }
}
