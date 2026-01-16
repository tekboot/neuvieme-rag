package com.ai.deepcode.controller;

import com.ai.deepcode.dto.ChatRequest;
import com.ai.deepcode.dto.ChatResponse;
import com.ai.deepcode.service.GithubFileService;
import com.ai.deepcode.service.OllamaService;
import com.ai.deepcode.service.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(
        origins = "http://localhost:4200",
        allowCredentials = "true"
)
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);
    private static final int MAX_CONTEXT_CHARS = 80_000;

    // Binary extensions to skip
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "tar", "gz", "rar", "7z",
            "exe", "dll", "so", "dylib",
            "mp3", "mp4", "avi", "mov", "wav",
            "ttf", "otf", "woff", "woff2", "eot"
    );

    private final OllamaService ollamaService;
    private final WorkspaceStore workspaceStore;
    private final GithubFileService githubFileService;

    public AiController(OllamaService ollamaService, WorkspaceStore workspaceStore, GithubFileService githubFileService) {
        this.ollamaService = ollamaService;
        this.workspaceStore = workspaceStore;
        this.githubFileService = githubFileService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("[AiController] Received chat request: message='{}', context.mode='{}'",
                truncate(request.message(), 50),
                request.context() != null ? request.context().mode() : "null");

        if (request.context() != null && request.context().files() != null) {
            log.info("[AiController] Context files count: {}", request.context().files().size());
            for (var f : request.context().files()) {
                log.info("[AiController]   - source={}, path={}, github={}",
                        f.source(), f.path(), f.github() != null ? f.github().owner() + "/" + f.github().repo() : "null");
            }
        }

        String prompt = buildPrompt(request);
        log.info("[AiController] Built prompt length: {} chars", prompt.length());

        String answer = ollamaService.generate(prompt);
        return new ChatResponse(answer);
    }

    private String buildPrompt(ChatRequest request) {
        ChatRequest.Context ctx = request.context();

        // No context
        if (ctx == null) {
            return request.message();
        }

        String mode = ctx.mode();
        List<ChatRequest.ContextFile> files = ctx.files();

        if ("all".equals(mode)) {
            // Include all device files from workspace (with size limit)
            List<ChatRequest.ContextFile> allDeviceFiles = new ArrayList<>();
            for (String path : workspaceStore.getAllPaths()) {
                allDeviceFiles.add(new ChatRequest.ContextFile("device", path, null));
            }
            return buildPromptWithContextFiles(request.message(), allDeviceFiles);
        }

        if ("selected".equals(mode) && files != null && !files.isEmpty()) {
            return buildPromptWithContextFiles(request.message(), files);
        }

        // Fallback: just the message
        return request.message();
    }

    private String buildPromptWithContextFiles(String userMessage, List<ChatRequest.ContextFile> files) {
        StringBuilder contextBuilder = new StringBuilder();
        StringBuilder failureReasons = new StringBuilder();
        int totalChars = 0;
        int successCount = 0;
        int failCount = 0;

        for (ChatRequest.ContextFile file : files) {
            // Skip binary files
            if (isBinaryFile(file.path())) {
                log.info("[AiController] Skipping binary file: {}", file.path());
                continue;
            }

            log.info("[AiController] Fetching content for: source={}, path={}, github={}",
                    file.source(), file.path(),
                    file.github() != null ? file.github().owner() + "/" + file.github().repo() : "null");

            // fetchFileContent may throw ResponseStatusException for GitHub auth issues
            // Let it propagate to return proper 401/403/404 to client
            String content = fetchFileContent(file);

            if (content == null) {
                String reason = String.format("%s:%s (fetch failed)", file.source(), file.path());
                log.warn("[AiController] FAILED to fetch content for: {}", reason);
                failureReasons.append("- ").append(reason).append("\n");
                failCount++;
                continue;
            }

            // Check size limit
            if (totalChars + content.length() > MAX_CONTEXT_CHARS) {
                contextBuilder.append("\n[... additional files truncated due to size limit ...]\n");
                log.info("[AiController] Truncating context at {} chars", totalChars);
                break;
            }

            // Build file header with source info
            String fileHeader = buildFileHeader(file);
            contextBuilder.append("--- ").append(fileHeader).append(" ---\n");
            contextBuilder.append(content).append("\n\n");
            totalChars += content.length();
            successCount++;

            log.info("[AiController] SUCCESS: Added file to context: {} ({} chars, preview: '{}')",
                    file.path(), content.length(),
                    content.substring(0, Math.min(60, content.length())).replace("\n", "\\n"));
        }

        log.info("[AiController] === CONTEXT SUMMARY: {} files loaded, {} failed, {} total chars ===",
                successCount, failCount, totalChars);

        // If ALL files failed to load, return an explicit error message
        if (contextBuilder.length() == 0 && failCount > 0) {
            log.error("[AiController] ALL context files failed to load! Returning CONTEXT_LOAD_ERROR");
            return """
                    CONTEXT_LOAD_ERROR: The user selected %d file(s) but all failed to load.

                    Failed files:
                    %s

                    Possible causes:
                    - For GitHub files: User may not be logged in via GitHub OAuth, or token expired
                    - For device files: Files may not have been uploaded to the workspace

                    User's original request was: %s

                    Please respond by explaining that you cannot access the requested files and ask the user to:
                    1. Ensure they are logged in via GitHub (for GitHub files)
                    2. Re-import device files (for local files)
                    """.formatted(failCount, failureReasons.toString(), userMessage);
        }

        // If some files loaded but some failed, include a note
        String failureNote = "";
        if (failCount > 0) {
            failureNote = """

                    NOTE: %d file(s) could not be loaded:
                    %s
                    """.formatted(failCount, failureReasons.toString());
        }

        if (contextBuilder.length() == 0) {
            log.warn("[AiController] No file content available, sending message only");
            return userMessage;
        }

        return """
                You are an AI coding assistant. Answer based on the provided project files.

                CONTEXT FILES:
                %s
                ---
                %s
                USER REQUEST:
                %s

                Provide a helpful, specific answer based on the actual file contents above. Quote specific lines when relevant.
                """.formatted(contextBuilder.toString(), failureNote, userMessage);
    }

    /**
     * Fetch file content from either device storage or GitHub.
     * For GitHub files, this may throw ResponseStatusException (401, 403, 404, 500)
     * which should propagate to the client.
     */
    private String fetchFileContent(ChatRequest.ContextFile file) {
        if ("device".equals(file.source())) {
            // Fetch from local workspace store
            String content = workspaceStore.getFileAsString(file.path());
            if (content != null) {
                log.debug("[AiController] Loaded device file: {} ({} chars)", file.path(), content.length());
            }
            return content;
        }

        if ("github".equals(file.source()) && file.github() != null) {
            // Fetch from GitHub API - may throw ResponseStatusException
            ChatRequest.GithubRef gh = file.github();
            return githubFileService.getFileContent(
                    gh.owner(),
                    gh.repo(),
                    file.path(),
                    gh.branch(),
                    gh.subPath()
            );
        }

        log.warn("[AiController] Unknown source or missing github ref: {}", file.source());
        return null;
    }

    private String buildFileHeader(ChatRequest.ContextFile file) {
        if ("github".equals(file.source()) && file.github() != null) {
            return "GitHub: " + file.github().owner() + "/" + file.github().repo() + " - " + file.path();
        }
        return "Device: " + file.path();
    }

    private boolean isBinaryFile(String path) {
        if (path == null) return true;
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) return false;
        String ext = path.substring(lastDot + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
