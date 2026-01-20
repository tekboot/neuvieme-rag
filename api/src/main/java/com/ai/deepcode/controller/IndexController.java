package com.ai.deepcode.controller;

import com.ai.deepcode.dto.*;
import com.ai.deepcode.entity.*;
import com.ai.deepcode.repository.*;
import com.ai.deepcode.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private final FileContentService fileContentService;
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;

    public IndexController(IndexingService indexingService,
            FileContentService fileContentService,
            ProjectRepository projectRepository,
            ProjectFileRepository projectFileRepository) {
        this.indexingService = indexingService;
        this.fileContentService = fileContentService;
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
    }

    /**
     * Start indexing a project.
     * POST /api/index/project
     */
    @PostMapping("/project")
    public ResponseEntity<Map<String, Object>> indexProject(
            @RequestBody IndexRequest request,
            Authentication auth) {
        log.info("[Index] START projectId={} mode={} files={} embedModel={} chunkSize={} overlap={} auth={}",
                request.projectId(),
                request.mode(),
                request.files() != null ? request.files().size() : 0,
                request.embedModel(),
                request.chunkSize(),
                request.chunkOverlap(),
                auth != null ? auth.getClass().getSimpleName() : "null");

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
                    // Pass authentication for GitHub file fetching
                    String content = fileContentService.fetchContent(file, auth);

                    if (content != null && !content.isEmpty()) {
                        fileContents.put(path, content);
                    } else {
                        errors.put(path, "Content unavailable or non-text eligible");
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

        // Ensure project exists in database
        Project project = projectRepository.findById(request.projectId()).orElse(null);
        if (project == null) {
            project = new Project();
            project.setId(request.projectId());
            project.setName(request.projectId().toString());
            project.setDisplayName("Project " + request.projectId().toString().substring(0, 8));
            project.setSource(request.source() != null ? request.source() : "device");

            if (request.files() != null && !request.files().isEmpty()) {
                var firstFile = request.files().get(0);
                if (firstFile.github() != null) {
                    project.setGithubOwner(firstFile.github().owner());
                    project.setGithubRepo(firstFile.github().repo());
                    project.setGithubBranch(firstFile.github().branch());
                }
            }
            project = projectRepository.save(project);
        }

        // PERSIST FILE TREE for future 'mode=all' RAG search
        if (request.files() != null && !request.files().isEmpty()) {
            final Project finalProject = project;
            // Best effort: only insert new ones or overwrite? Let's just update all.
            for (RagFileRef f : request.files()) {
                try {
                    ProjectFile pf = new ProjectFile();
                    pf.setProject(finalProject);
                    pf.setPath(f.path());
                    pf.setSource(f.source());
                    if (f.github() != null) {
                        pf.setGithubOwner(f.github().owner());
                        pf.setGithubRepo(f.github().repo());
                        pf.setGithubBranch(f.github().branch());
                        pf.setGithubSubPath(f.github().subPath());
                    }
                    projectFileRepository.save(pf);
                } catch (Exception e) {
                    // Ignore duplicate key errors if index already has them
                }
            }
        }

        // Proceed to index
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
