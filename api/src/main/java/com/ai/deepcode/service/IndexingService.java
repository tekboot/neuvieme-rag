package com.ai.deepcode.service;

import com.ai.deepcode.entity.Chunk;
import com.ai.deepcode.entity.IndexStatus;
import com.ai.deepcode.entity.IndexingStatus;
import com.ai.deepcode.entity.Project;
import com.ai.deepcode.repository.ChunkRepository;
import com.ai.deepcode.repository.IndexStatusRepository;
import com.ai.deepcode.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for indexing project files into vector embeddings.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final ProjectRepository projectRepository;
    private final IndexStatusRepository indexStatusRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    public IndexingService(
            ProjectRepository projectRepository,
            IndexStatusRepository indexStatusRepository,
            ChunkRepository chunkRepository,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            JdbcTemplate jdbcTemplate) {
        this.projectRepository = projectRepository;
        this.indexStatusRepository = indexStatusRepository;
        this.chunkRepository = chunkRepository;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Start indexing a project asynchronously.
     */
    @Async
    public CompletableFuture<Void> indexProjectAsync(
            UUID projectId,
            Map<String, String> fileContents,
            String embedModel,
            int chunkSize,
            int chunkOverlap) {

        try {
            indexProject(projectId, fileContents, embedModel, chunkSize, chunkOverlap);
        } catch (Exception e) {
            log.error("[IndexingService] Async indexing failed for project {}: {}", projectId, e.getMessage());
            updateStatusToError(projectId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Index a project's files synchronously.
     */
    @Transactional
    public void indexProject(
            UUID projectId,
            Map<String, String> fileContents,
            String embedModel,
            int chunkSize,
            int chunkOverlap) {

        log.info("╔══════════════════════════════════════════════════════════════════════════════");
        log.info("║ [INDEXING START] Project: {}", projectId);
        log.info("║   Total files discovered: {}", fileContents.size());
        log.info("║   Embedding model: {}", embedModel);
        log.info("║   Chunk size: {}, Overlap: {}", chunkSize, chunkOverlap);
        log.info("╚══════════════════════════════════════════════════════════════════════════════");

        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            log.error("[IndexingService] Project not found in database: {}", projectId);
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        // Create or update index status
        IndexStatus status = indexStatusRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    IndexStatus newStatus = new IndexStatus();
                    newStatus.setProject(project);
                    return newStatus;
                });

        status.setStatus(IndexingStatus.IN_PROGRESS);
        status.setTotalFiles(fileContents.size());
        status.setIndexedFiles(0);
        status.setFailedFiles(0);
        status.setTotalChunks(0);
        status.setEmbedModel(embedModel);
        status.setChunkSize(chunkSize);
        status.setChunkOverlap(chunkOverlap);
        status.setStartedAt(OffsetDateTime.now());
        status.setErrorMessage(null);
        indexStatusRepository.save(status);

        // Clear existing chunks for this project
        log.info("[INDEXING] Clearing existing chunks for project {}", projectId);
        chunkRepository.deleteByProjectId(projectId);

        int indexedFiles = 0;
        int failedFiles = 0;
        int totalChunks = 0;
        int totalFilesToIndex = fileContents.size();

        log.info("[INDEXING] Beginning file processing loop for {} files", totalFilesToIndex);

        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();

            try {
                // Chunk the file content
                List<ChunkingService.ChunkResult> chunks = chunkingService.chunkFileContent(
                        filePath, content, chunkSize, chunkOverlap);

                int fileChunkCount = 0;

                // Generate embeddings and save chunks
                for (ChunkingService.ChunkResult chunkResult : chunks) {
                    float[] embedding = embeddingService.generateEmbedding(chunkResult.content(), embedModel);

                    String vectorCol = "embedding_768";
                    if (embedding.length == 384)
                        vectorCol = "embedding_384";
                    else if (embedding.length == 1024)
                        vectorCol = "embedding_1024";

                    // Use JdbcTemplate for native insert with vector cast
                    jdbcTemplate.update(
                            String.format(
                                    "INSERT INTO chunks (id, project_id, file_path, chunk_index, content, token_count, %s, created_at) "
                                            +
                                            "VALUES (?, ?, ?, ?, ?, ?, cast(? as vector), NOW())",
                                    vectorCol),
                            UUID.randomUUID(),
                            projectId,
                            filePath,
                            chunkResult.index(),
                            chunkResult.content(),
                            chunkResult.tokenEstimate(),
                            EmbeddingService.toVectorString(embedding));

                    totalChunks++;
                    fileChunkCount++;
                }

                indexedFiles++;

                // Log progress every 10 files or at completion
                if (indexedFiles % 10 == 0 || indexedFiles == totalFilesToIndex) {
                    int progress = (int) ((indexedFiles * 100.0) / totalFilesToIndex);
                    log.info("[INDEXING PROGRESS] {}/{} files ({}%), {} chunks created | Current: {}",
                            indexedFiles, totalFilesToIndex, progress, totalChunks, filePath);
                }

                status.setIndexedFiles(indexedFiles);
                status.setTotalChunks(totalChunks);
                indexStatusRepository.save(status);

            } catch (Exception e) {
                failedFiles++;
                log.error("[INDEXING ERROR] Failed to index file {}: {}", filePath, e.getMessage());
                status.setFailedFiles(failedFiles);
                indexStatusRepository.save(status);
            }
        }

        // Mark as completed
        if (status.getFailedFiles() > 0) {
            status.setStatus(indexedFiles == 0 ? IndexingStatus.FAILED : IndexingStatus.COMPLETED_WITH_ERRORS);
            if (indexedFiles == 0)
                status.setErrorMessage("All files failed to index");
        } else {
            status.setStatus(IndexingStatus.COMPLETED);
        }

        status.setCompletedAt(OffsetDateTime.now());
        indexStatusRepository.save(status);

        log.info("╔══════════════════════════════════════════════════════════════════════════════");
        log.info("║ [INDEXING COMPLETE] Project: {}", projectId);
        log.info("║   Status: {}", status.getStatus());
        log.info("║   Files indexed: {}/{}", indexedFiles, totalFilesToIndex);
        log.info("║   Failed files: {}", failedFiles);
        log.info("║   Total chunks created: {}", totalChunks);
        log.info("║   Embedding model used: {}", embedModel);
        log.info("║   DB inserts confirmed: {} rows in chunks table", totalChunks);
        log.info("╚══════════════════════════════════════════════════════════════════════════════");
    }

    public IndexStatus getStatus(UUID projectId) {
        return indexStatusRepository.findByProjectId(projectId).orElse(null);
    }

    @Transactional
    public void updateStatusToError(UUID projectId, String errorMessage) {
        indexStatusRepository.findByProjectId(projectId).ifPresent(status -> {
            status.setStatus(IndexingStatus.FAILED);
            status.setErrorMessage(errorMessage);
            indexStatusRepository.save(status);
        });
    }

    @Transactional
    public void deleteProjectIndex(UUID projectId) {
        chunkRepository.deleteByProjectId(projectId);
        indexStatusRepository.findByProjectId(projectId)
                .ifPresent(indexStatusRepository::delete);
    }
}
