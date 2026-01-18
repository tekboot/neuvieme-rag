package com.ai.deepcode.controller;

import com.ai.deepcode.dto.*;
import com.ai.deepcode.entity.IndexStatus;
import com.ai.deepcode.entity.IndexingStatus;
import com.ai.deepcode.repository.IndexStatusRepository;
import com.ai.deepcode.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:4200", allowCredentials = "true")
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
            "ttf", "otf", "woff", "woff2", "eot");

    private final OllamaService ollamaService;
    private final WorkspaceStore workspaceStore;
    private final GithubFileService githubFileService;
    private final VectorSearchService vectorSearchService;
    private final IndexingService indexingService;
    private final IndexStatusRepository indexStatusRepository;

    public AiController(OllamaService ollamaService, WorkspaceStore workspaceStore,
            GithubFileService githubFileService, VectorSearchService vectorSearchService,
            IndexingService indexingService, IndexStatusRepository indexStatusRepository) {
        this.ollamaService = ollamaService;
        this.workspaceStore = workspaceStore;
        this.githubFileService = githubFileService;
        this.vectorSearchService = vectorSearchService;
        this.indexingService = indexingService;
        this.indexStatusRepository = indexStatusRepository;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("[AiController] Received chat request: message='{}', context.mode='{}', model='{}'",
                truncate(request.message(), 50),
                request.context() != null ? request.context().mode() : "null",
                request.model() != null ? request.model() : "default");

        if (request.context() != null && request.context().files() != null) {
            log.info("[AiController] Context files count: {}", request.context().files().size());
            for (var f : request.context().files()) {
                log.info("[AiController]   - source={}, path={}, github={}",
                        f.source(), f.path(),
                        f.github() != null ? f.github().owner() + "/" + f.github().repo() : "null");
            }
        }

        String prompt = buildPrompt(request);
        log.info("[AiController] Built prompt length: {} chars", prompt.length());

        // Use the selected model or fall back to default
        String answer = ollamaService.generate(prompt, request.model());
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
                """
                .formatted(contextBuilder.toString(), failureNote, userMessage);
    }

    private String fetchFileContent(ChatRequest.ContextFile file) {
        if ("device".equals(file.source())) {
            return workspaceStore.getFileAsString(file.path());
        }
        if ("github".equals(file.source()) && file.github() != null) {
            ChatRequest.GithubRef gh = file.github();
            return githubFileService.getFileContent(gh.owner(), gh.repo(), file.path(), gh.branch(), gh.subPath());
        }
        return null;
    }

    private String fetchFileContent(RagFileRef file) {
        if ("device".equals(file.source())) {
            return workspaceStore.getFileAsString(file.path());
        }
        if ("github".equals(file.source()) && file.github() != null) {
            GithubRef gh = file.github();
            return githubFileService.getFileContent(gh.owner(), gh.repo(), file.path(), gh.branch(), gh.subPath());
        }
        return null;
    }

    private String buildFileHeader(ChatRequest.ContextFile file) {
        if ("github".equals(file.source()) && file.github() != null) {
            return "GitHub: " + file.github().owner() + "/" + file.github().repo() + " - " + file.path();
        }
        return "Device: " + file.path();
    }

    private boolean isBinaryFile(String path) {
        if (path == null)
            return true;
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0)
            return false;
        String ext = path.substring(lastDot + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }

    private String truncate(String s, int maxLen) {
        if (s == null)
            return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * RAG-augmented chat endpoint.
     * Uses vector similarity search to find relevant code chunks,
     * then includes them as context for the LLM.
     *
     * POST /api/ai/chat-rag
     */
    @PostMapping("/chat-rag")
    public RagChatResponse chatWithRag(@RequestBody RagChatRequest request) {
        log.info("[AiController] RAG chat request: strategy={}, message='{}', projects={}, topK={}, model='{}'",
                request.strategy(),
                truncate(request.message(), 50),
                request.projectIds() != null ? request.projectIds().size() : 0,
                request.topK(),
                request.model() != null ? request.model() : "default");

        // VALIDATION
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_MESSAGE: Message is required");
        }

        if (request.projectIds() == null || request.projectIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MISSING_PROJECTS: At least one project ID is required");
        }

        if ("selected".equalsIgnoreCase(request.mode()) && (request.files() == null || request.files().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MISSING_FILES: Mode 'selected' requires at least one file");
        }

        if ("use_existing".equalsIgnoreCase(request.strategy())
                && (request.projectIds() == null || request.projectIds().isEmpty())) {
            // redundant due to check above, but following user requirement literally
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MISSING_PROJECTS: Strategy 'use_existing' requires projectIds");
        }

        List<String> logEntries = new ArrayList<>();
        boolean usedExisting = false;
        boolean indexedNow = false;
        int filesIndexedTotal = 0;
        int chunksCreatedTotal = 0;

        UUID mainProjectId = request.projectIds().get(0);
        String embedModel = request.embedModel() != null ? request.embedModel() : "nomic-embed-text";
        int chunkSize = request.chunkSize() != null ? request.chunkSize() : 1000;
        int chunkOverlap = request.chunkOverlap() != null ? request.chunkOverlap() : 200;

        if ("use_existing".equalsIgnoreCase(request.strategy())) {
            logEntries.add("Checking existing index for project: " + mainProjectId);
            IndexStatus status = indexStatusRepository.findByProjectId(mainProjectId).orElse(null);

            if (status != null && status.getStatus() == IndexingStatus.COMPLETED) {
                boolean modelMatch = embedModel.equals(status.getEmbedModel());
                boolean chunkMatch = chunkSize == status.getChunkSize() && chunkOverlap == status.getChunkOverlap();

                if (modelMatch && chunkMatch) {
                    logEntries.add("Matching index found: " + status.getTotalChunks() + " chunks, model="
                            + status.getEmbedModel());
                    usedExisting = true;
                } else {
                    String reason = !modelMatch
                            ? "Model mismatch (request=" + embedModel + ", stored=" + status.getEmbedModel() + ")"
                            : "Chunk settings mismatch";
                    logEntries.add("Index found but settings mismatch: " + reason);
                }
            } else {
                logEntries.add("No completed index found for project.");
            }
        }

        if (!usedExisting) {
            logEntries.add("Triggering automatic indexing...");

            Map<String, String> fileContents = new HashMap<>();
            List<RagFileRef> filesToFetch = ("selected".equalsIgnoreCase(request.mode()) && request.files() != null)
                    ? request.files()
                    : getAllProjectFiles(mainProjectId);

            logEntries.add("Fetching content for " + filesToFetch.size() + " files...");
            for (RagFileRef file : filesToFetch) {
                try {
                    String content = fetchFileContent(file);
                    if (content != null && !content.isEmpty()) {
                        fileContents.put(file.path(), content);
                    }
                } catch (Exception e) {
                    log.error("[AiController] Error fetching {}: {}", file.path(), e.getMessage());
                }
            }

            if (fileContents.isEmpty()) {
                logEntries.add("No indexable text contents available. Cannot re-index.");
            } else {
                logEntries.add("Indexing " + fileContents.size() + " files...");
                indexingService.indexProject(mainProjectId, fileContents, embedModel, chunkSize, chunkOverlap);

                IndexStatus status = indexStatusRepository.findByProjectId(mainProjectId).orElse(null);
                if (status != null) {
                    filesIndexedTotal = status.getIndexedFiles();
                    chunksCreatedTotal = status.getTotalChunks();
                    indexedNow = true;
                    logEntries.add("Indexing complete: " + chunksCreatedTotal + " chunks created.");
                }
            }
        }

        // Perform vector similarity search
        List<String> fileFilter = ("selected".equalsIgnoreCase(request.mode()) && request.files() != null)
                ? request.files().stream().map(RagFileRef::path).toList()
                : null;

        List<VectorSearchService.SearchResult> searchResults = vectorSearchService.searchAcrossProjects(
                request.projectIds(),
                request.message(),
                request.topK(),
                embedModel,
                fileFilter);

        logEntries.add("Retrieved " + searchResults.size() + " relevant chunks.");

        // Build context from search results
        String ragContext = vectorSearchService.buildContextFromResults(searchResults);

        // Build the final prompt with RAG context
        String prompt = buildRagPrompt(request.message(), ragContext);

        // Generate response using the LLM
        String answer = ollamaService.generate(prompt, request.model());

        return new RagChatResponse(
                answer,
                new RagChatResponse.RagMetadata(
                        usedExisting ? "use_existing" : "reindex",
                        usedExisting,
                        indexedNow,
                        searchResults.size(),
                        chunksCreatedTotal,
                        filesIndexedTotal,
                        logEntries));
    }

    private List<RagFileRef> getAllProjectFiles(UUID projectId) {
        List<RagFileRef> list = new ArrayList<>();
        for (String path : workspaceStore.getAllPaths()) {
            list.add(new RagFileRef("device", path, null));
        }
        return list;
    }

    private String buildRagPrompt(String userMessage, String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return """
                    You are an AI coding assistant. The user is asking about a codebase, but no relevant context was found in the indexed files.

                    USER REQUEST:
                    %s

                    Please respond by acknowledging that you don't have specific context about the code, and offer to help if they can provide more details or re-index the project.
                    """
                    .formatted(userMessage);
        }

        return """
                You are an AI coding assistant. Answer the user's question based on the relevant code context retrieved from their indexed project files.

                %s

                USER REQUEST:
                %s

                Provide a helpful, specific answer based on the code context above. Reference specific files and code when relevant. If the context doesn't contain enough information to fully answer, acknowledge what you can determine and what additional information might be needed.
                """
                .formatted(ragContext, userMessage);
    }
}
