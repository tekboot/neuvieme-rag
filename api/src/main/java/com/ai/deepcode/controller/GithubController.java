package com.ai.deepcode.controller;

import com.ai.deepcode.api.GithubApiClient;
import com.ai.deepcode.dto.*;
import com.ai.deepcode.entity.*;
import com.ai.deepcode.repository.*;
import com.ai.deepcode.service.*;
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
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final IndexingService indexingService;
    private final FileContentService fileContentService;

    public GithubController(GithubTokenService tokenService, GithubApiClient github,
            ProjectRepository projectRepository,
            ProjectFileRepository projectFileRepository,
            IndexingService indexingService,
            FileContentService fileContentService) {
        this.tokenService = tokenService;
        this.github = github;
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
        this.indexingService = indexingService;
        this.fileContentService = fileContentService;
    }

    /**
     * Verify GitHub connection and return auth status.
     * GET /api/github/status
     */
    @GetMapping("/status")
    public ResponseEntity<?> checkStatus(Authentication auth) {
        log.info("[GithubStatus] Checking connection status");

        if (auth == null) {
            return ResponseEntity.ok(Map.of("connected", false, "reason", "Not authenticated"));
        }

        try {
            String token = tokenService.getAccessToken(auth);
            if (token == null || token.isBlank()) {
                return ResponseEntity.ok(Map.of("connected", false, "reason", "No GitHub token"));
            }

            // Verify token by making a simple API call
            github.listRepos(token);
            return ResponseEntity.ok(Map.of("connected", true));

        } catch (Exception e) {
            log.warn("[GithubStatus] Connection check failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("connected", false, "reason", e.getMessage()));
        }
    }

    /**
     * List repositories for a specific owner (or authenticated user if no owner).
     * GET /api/github/repos?owner=...
     */
    @GetMapping("/repos")
    public ResponseEntity<?> listRepos(Authentication auth, @RequestParam(required = false) String owner) {
        log.info("[GithubRepos] LIST repos owner={}", owner);

        if (auth == null) {
            log.error("[GithubRepos] FAIL: No authentication provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_AUTH_MISSING", "message",
                            "GitHub authentication required. Please connect your GitHub account."));
        }

        String token;
        try {
            token = tokenService.getAccessToken(auth);
            log.info("[GithubRepos] Auth OK, token length={}", token != null ? token.length() : 0);
        } catch (Exception e) {
            log.error("[GithubRepos] FAIL: Could not get access token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_ERROR", "message",
                            "Failed to get GitHub token: " + e.getMessage()));
        }

        if (token == null || token.isBlank()) {
            log.error("[GithubRepos] FAIL: Token is null or blank");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_MISSING", "message",
                            "GitHub token is missing. Please reconnect your GitHub account."));
        }

        try {
            List<Map<String, Object>> repos;
            if (owner != null && !owner.isBlank()) {
                repos = github.listReposByOwner(token, owner);
            } else {
                repos = github.listRepos(token);
            }

            List<GithubRepoInfo> result = repos.stream()
                    .map(GithubRepoInfo::fromApiResponse)
                    .toList();

            log.info("[GithubRepos] SUCCESS: returned {} repos for owner={}", result.size(), owner);
            return ResponseEntity.ok(result);

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.error("[GithubRepos] FAIL: Owner not found: {}", owner);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "GITHUB_OWNER_NOT_FOUND", "message",
                            "GitHub user or organization not found: " + owner));
        } catch (Exception e) {
            log.error("[GithubRepos] FAIL: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "GITHUB_API_ERROR", "message",
                            "Failed to list repositories: " + e.getMessage()));
        }
    }

    /**
     * Import a GitHub repository.
     * POST /api/github/import
     */
    @PostMapping("/import")
    public ResponseEntity<?> importRepo(Authentication auth, @RequestBody GithubImportRequest req) {
        log.info("[GithubImport] START owner={} repo={} branch={} subPath={} indexMode={} embedModel={}",
                req.owner(), req.repo(), req.branch(), req.subPath(), req.indexMode(), req.embedModel());

        // Validate authentication
        if (auth == null) {
            log.error("[GithubImport] FAIL: No authentication provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_AUTH_MISSING", "message",
                            "GitHub authentication required. Please connect your GitHub account."));
        }

        String token;
        try {
            token = tokenService.getAccessToken(auth);
            log.info("[GithubImport] Auth OK, token length={}", token != null ? token.length() : 0);
        } catch (Exception e) {
            log.error("[GithubImport] FAIL: Could not get access token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_ERROR", "message",
                            "Failed to get GitHub token: " + e.getMessage()));
        }

        if (token == null || token.isBlank()) {
            log.error("[GithubImport] FAIL: Token is null or blank");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_MISSING", "message",
                            "GitHub token is missing. Please reconnect your GitHub account."));
        }

        String owner = req.owner();
        String repo = req.repo();
        String branch = (req.branch() == null || req.branch().isBlank()) ? "HEAD" : req.branch();
        String subPath = normalize(req.subPath());

        try {
            // Persist Project
            log.info("[GithubImport] Looking up or creating project for {}/{} branch={}", owner, repo, branch);
            Project project = projectRepository
                    .findByGithubOwnerAndGithubRepoAndGithubBranch(owner, repo, branch)
                    .orElseGet(() -> {
                        log.info("[GithubImport] Creating new project record");
                        Project p = new Project();
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

            // Extract only blobs (files) - filter by type
            List<String> paths = items.stream()
                    .filter(m -> "blob".equals(m.get("type")))
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

            // PERSIST FILE TREE for RAG
            log.info("[GithubImport] Persisting {} file entries to project_files", paths.size());
            final Project finalProject = project;
            final String finalSubPath = req.subPath(); // Use original subPath for context
            for (String p : paths) {
                try {
                    ProjectFile pf = new ProjectFile();
                    pf.setProject(finalProject);
                    pf.setPath(p);
                    pf.setSource("github");
                    pf.setGithubOwner(owner);
                    pf.setGithubRepo(repo);
                    pf.setGithubBranch(branch);
                    pf.setGithubSubPath(finalSubPath);
                    projectFileRepository.save(pf);
                } catch (Exception e) {
                    // Ignore or log. Could be duplicate if refreshing.
                }
            }

            log.info("[GithubImport] SUCCESS projectId={} files={} treeRoots={}",
                    project.getId(), paths.size(), tree.size());

            // Handle pre-indexing if requested
            boolean indexingStarted = false;
            if (req.shouldPreIndex()) {
                log.info("[GithubImport] Pre-indexing requested, starting indexing for project {}", project.getId());
                try {
                    // Fetch file contents and index
                    Map<String, String> fileContents = new HashMap<>();
                    for (String filePath : paths) {
                        try {
                            RagFileRef ref = new RagFileRef("github", filePath,
                                    new GithubRef(owner, repo, branch, finalSubPath));
                            String content = fileContentService.fetchContent(ref, auth);
                            if (content != null && !content.isEmpty()) {
                                fileContents.put(filePath, content);
                            }
                        } catch (Exception e) {
                            log.warn("[GithubImport] Failed to fetch content for {}: {}", filePath, e.getMessage());
                        }
                    }

                    if (!fileContents.isEmpty()) {
                        String embedModel = req.getEmbedModelOrDefault();
                        log.info("[GithubImport] Starting indexing with {} files using embedModel={}", fileContents.size(), embedModel);
                        indexingService.indexProjectAsync(project.getId(), fileContents, embedModel, 500, 50);
                        indexingStarted = true;
                    }
                } catch (Exception e) {
                    log.error("[GithubImport] Pre-indexing failed: {}", e.getMessage());
                }
            }

            // Build response with indexing status
            Map<String, Object> response = new HashMap<>();
            response.put("project", ProjectDto.from(project));
            response.put("tree", tree);
            response.put("indexingStarted", indexingStarted);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            log.error("[GithubImport] FAIL: GitHub API returned 401 Unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_UNAUTHORIZED", "message",
                            "GitHub token expired or invalid. Please reconnect your GitHub account."));
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            log.error("[GithubImport] FAIL: GitHub API returned 403 Forbidden");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "GITHUB_FORBIDDEN", "message",
                            "Access denied to repository. Check repository permissions."));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.error("[GithubImport] FAIL: GitHub API returned 404 Not Found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "GITHUB_NOT_FOUND", "message",
                            "Repository or branch not found: " + owner + "/" + repo + " branch=" + branch));
        } catch (Exception e) {
            log.error("[GithubImport] FAIL: Unexpected error: {} - {}", e.getClass().getSimpleName(), e.getMessage(),
                    e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "GITHUB_IMPORT_FAILED", "message", "Import failed: " + e.getMessage(),
                            "details", e.getClass().getSimpleName()));
        }
    }

    /**
     * List branches for a repository.
     * GET /api/github/branches?owner=...&repo=...
     */
    @GetMapping("/branches")
    public ResponseEntity<?> listBranches(Authentication auth,
            @RequestParam String owner,
            @RequestParam String repo) {
        log.info("[GithubBranches] LIST branches owner={} repo={}", owner, repo);

        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_AUTH_MISSING", "message",
                            "GitHub authentication required."));
        }

        String token;
        try {
            token = tokenService.getAccessToken(auth);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_ERROR", "message",
                            "Failed to get GitHub token: " + e.getMessage()));
        }

        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "GITHUB_TOKEN_MISSING", "message",
                            "GitHub token is missing."));
        }

        try {
            List<Map<String, Object>> branches = github.listBranches(token, owner, repo);
            // Extract just name for each branch
            List<Map<String, String>> result = branches.stream()
                    .map(b -> Map.of("name", (String) b.get("name")))
                    .toList();

            log.info("[GithubBranches] SUCCESS: returned {} branches", result.size());
            return ResponseEntity.ok(result);

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "GITHUB_NOT_FOUND", "message",
                            "Repository not found: " + owner + "/" + repo));
        } catch (Exception e) {
            log.error("[GithubBranches] FAIL: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "GITHUB_API_ERROR", "message",
                            "Failed to list branches: " + e.getMessage()));
        }
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
