package com.ai.deepcode.controller;

import com.ai.deepcode.api.GithubApiClient;
import com.ai.deepcode.dto.FileNode;
import com.ai.deepcode.dto.GithubImportRequest;
import com.ai.deepcode.dto.UpdateFileRequest;
import com.ai.deepcode.service.GithubTokenService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/github")
public class GithubController {

    private final GithubTokenService tokenService;
    private final GithubApiClient github;

    public GithubController(GithubTokenService tokenService, GithubApiClient github) {
        this.tokenService = tokenService;
        this.github = github;
    }

    @PostMapping("/import")
    public List<FileNode> importRepo(Authentication auth, @RequestBody GithubImportRequest req) {
        String token = tokenService.getAccessToken(auth);

        String owner = req.owner();
        String repo = req.repo();
        String branch = (req.branch() == null || req.branch().isBlank()) ? "HEAD" : req.branch();
        String subPath = normalize(req.subPath());

        Map<String, Object> treeResponse = github.getRepoTree(token, owner, repo, branch);

        List<Map<String, Object>> items = (List<Map<String, Object>>) treeResponse.getOrDefault("tree", List.of());

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

        return buildTree(paths);
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
            @RequestParam(required = false) String ref
    ) {
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
                sha
        );
    }

    // ---- helpers ----
    private static String normalize(String p) {
        if (p == null) return null;
        String x = p.trim().replace("\\", "/");
        while (x.startsWith("/")) x = x.substring(1);
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
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

                if (!isLast) current.type = "folder";
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
                    false
            )).toList();
        }
    }
}
