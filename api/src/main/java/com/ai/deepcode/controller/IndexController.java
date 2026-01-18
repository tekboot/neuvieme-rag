package com.ai.deepcode.controller;

import com.ai.deepcode.dto.RagFileRef;
import com.ai.deepcode.dto.IndexRequest;
import com.ai.deepcode.dto.IndexStatusResponse;
import com.ai.deepcode.entity.IndexStatus;
import com.ai.deepcode.entity.Project;
import com.ai.deepcode.exception.ApiError;
import com.ai.deepcode.repository.ProjectRepository;
import com.ai.deepcode.service.GithubFileService;
import com.ai.deepcode.service.IndexingService;
import com.ai.deepcode.service.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for project indexing operations.
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    private final IndexingService indexingService;
    private final WorkspaceStore workspaceStore;
    private final GithubFileService githubFileService;
    private final ProjectRepository projectRepository;

    public IndexController(IndexingService indexingService, WorkspaceStore workspaceStore,
            GithubFileService githubFileService,
            ProjectRepository projectRepository) {
        this.indexingService = indexingService;
        this.workspaceStore = workspaceStore;
        this.githubFileService = githubFileService;
        this.projectRepository = projectRepository;
    }

    /**
     * Start indexing a project.
     * POST /api/index/project
     *
     * Supports two modes:
     * 1. fileContents provided: use the provided content directly
     * 2. filePaths provided with source="device": fetch content from WorkspaceStore
     * 3. files provided with source="github": fetch content using GithubFileService
     */
    @PostMapping("/project")
    public ResponseEntity<Map<String, Object>> indexProject(@RequestBody IndexRequest request) {
        log.info("[Index] START projectId={} mode={} files={} embedModel={} chunkSize={} overlap={}",
                request.projectId(),
                request.mode(),
                request.files() != null ? request.files().size() : 0,
                request.embedModel(),
                request.chunkSize(),
                request.chunkOverlap());

        if (request.projectId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Project ID is required"));
        }

        Map<String, String> fileContents = request.fileContents() != null ? new HashMap<>(request.fileContents())
                : new HashMap<>();
        Map<String, String> errors = new HashMap<>();

        // Handle 'selected' mode with provided file list
        if ("selected".equals(request.mode()) && request.files() != null && !request.files().isEmpty()) {
            log.info("[Index] BOOTSTRAP: Fetching content for {} selected files", request.files().size());

            for (RagFileRef file : request.files()) {
                String path = file.path();
                try {
                    // 1. Check text eligibility by extension/filename
                    if (!isTextEligible(path)) {
                        log.warn("[Index] SKIP: File not text-eligible: {}", path);
                        errors.put(path, "Filtered: non-text or binary file type");
                        continue;
                    }

                    String content = null;
                    if ("github".equalsIgnoreCase(file.source())) {
                        if (file.github() != null) {
                            log.info("[Index] Fetching GitHub file: {}/{}", file.github().repo(), path);
                            content = githubFileService.getFileContent(
                                    file.github().owner(),
                                    file.github().repo(),
                                    path,
                                    file.github().branch(),
                                    file.github().subPath());
                        } else {
                            log.warn("[Index] GitHub file missing metadata: {}", path);
                            errors.put(path, "Missing GitHub metadata");
                        }
                    } else if ("device".equalsIgnoreCase(file.source())) {
                        log.info("[Index] Fetching Device file: {}", path);
                        content = workspaceStore.getFileAsString(path);
                    }

                    if (content != null && !content.isEmpty()) {
                        fileContents.put(path, content);
                        log.info("[Index] FETCH OK: {} ({} chars)", path, content.length());
                    } else {
                        log.warn("[Index] Content is null or empty for file: {}", path);
                        errors.put(path,
                                content == null ? "Content unavailable (404 or fetch error)" : "File is empty");
                    }
                } catch (Exception e) {
                    log.error("[Index] Error fetching file {}: {}", path, e.getMessage());
                    errors.put(path, e.getMessage());
                }
            }
        }

        if (fileContents.isEmpty()) {
            log.warn("[Index] Aborting: No indexable text contents available. Errors: {}", errors);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "NO_INDEXABLE_CONTENT",
                    "message", "No file contents available for indexing. Please select valid text files.",
                    "details", errors));
        }

        log.info("[Index] Successfully fetched {} files, proceeding to index", fileContents.size());

        // Ensure project exists in database (create if not)
        Project project = projectRepository.findById(request.projectId()).orElse(null);
        if (project == null) {
            log.info("[Index] Project {} not found in DB, creating new project record", request.projectId());
            project = new Project();
            project.setId(request.projectId());
            project.setName(request.projectId().toString());
            project.setDisplayName("Project " + request.projectId().toString().substring(0, 8));
            project.setSource(request.source() != null ? request.source() : "device");
            project.setFileCount(fileContents.size());

            // Set github metadata if available from first file
            if (request.files() != null && !request.files().isEmpty()) {
                var firstFile = request.files().get(0);
                if (firstFile.github() != null) {
                    project.setGithubOwner(firstFile.github().owner());
                    project.setGithubRepo(firstFile.github().repo());
                    project.setGithubBranch(firstFile.github().branch());
                }
            }

            projectRepository.save(project);
            log.info("[Index] Created project record: id={}, source={}", project.getId(), project.getSource());
        } else {
            log.info("[Index] Project {} already exists in DB", request.projectId());
        }

        // Proceed to index the fetched content
        indexingService.indexProject(
                request.projectId(),
                fileContents,
                request.embedModel(),
                request.chunkSize(),
                request.chunkOverlap());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Indexing started");
        response.put("projectId", request.projectId());
        response.put("fileCount", fileContents.size());
        if (!errors.isEmpty()) {
            response.put("partialErrors", errors);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get indexing status for a project.
     * GET /api/index/status/{projectId}
     */
    @GetMapping("/status/{projectId}")
    public ResponseEntity<IndexStatusResponse> getStatus(@PathVariable UUID projectId) {
        log.debug("[IndexController] Status request for project {}", projectId);

        IndexStatus status = indexingService.getStatus(projectId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(IndexStatusResponse.from(status));
    }

    /**
     * Delete index for a project.
     * DELETE /api/index/project/{projectId}
     */
    @DeleteMapping("/project/{projectId}")
    public ResponseEntity<Map<String, String>> deleteIndex(@PathVariable UUID projectId) {
        log.info("[IndexController] Delete index for project {}", projectId);

        indexingService.deleteProjectIndex(projectId);

        return ResponseEntity.ok(Map.of(
                "message", "Index deleted",
                "projectId", projectId.toString()));
    }

    private boolean isTextEligible(String path) {
        if (path == null)
            return false;
        String fileName = path.toLowerCase();

        // Exact filename matches
        Set<String> validNames = Set.of("license", "dockerfile", "makefile", "procfile", "gemfile", "pipfile");
        String justName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf("/") + 1) : fileName;
        if (validNames.contains(justName))
            return true;

        // Extension matches
        List<String> validExtensions = List.of(
                ".md", ".txt", ".java", ".ts", ".js", ".json", ".yml", ".yaml", ".xml",
                ".properties", ".env", ".gitignore", ".py", ".c", ".cpp", ".h", ".hpp",
                ".cs", ".sh", ".bash", ".sql", ".css", ".html", ".kt", ".rs", ".go");

        for (String ext : validExtensions) {
            if (fileName.endsWith(ext))
                return true;
        }

        return false;
    }
}
