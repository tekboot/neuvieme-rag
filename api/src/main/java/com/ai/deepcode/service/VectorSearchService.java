package com.ai.deepcode.service;

import com.ai.deepcode.dto.ChunkHitDto;
import com.ai.deepcode.repository.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service for performing vector similarity search on indexed chunks.
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
    private static final int DEFAULT_TOP_K = 5;

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    public VectorSearchService(ChunkRepository chunkRepository, EmbeddingService embeddingService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * Search result containing chunk content and metadata.
     */
    public record SearchResult(
            String filePath,
            int chunkIndex,
            String content,
            UUID projectId) {
    }

    /**
     * Search for similar chunks within a single project.
     */
    public List<SearchResult> search(UUID projectId, String query, int topK) {
        return search(projectId, query, topK, null, null);
    }

    /**
     * Search for similar chunks within a single project using specified embed
     * model and file filters.
     */
    public List<SearchResult> search(UUID projectId, String query, int topK, String embedModel,
            List<String> filePaths) {
        if (query == null || query.isBlank()) {
            log.warn("[VectorSearchService] Empty query provided");
            return List.of();
        }

        int limit = topK > 0 ? topK : DEFAULT_TOP_K;

        log.info("[VectorSearchService] Searching project {} with query: '{}'", projectId,
                query.length() > 50 ? query.substring(0, 50) + "..." : query);

        // Generate embedding for query
        float[] queryEmbedding = embeddingService.generateEmbedding(query, embedModel);
        String vectorString = EmbeddingService.toVectorString(queryEmbedding);

        // Determine vector column
        String vectorColumn = "embedding_768"; // default
        if (queryEmbedding.length == 384)
            vectorColumn = "embedding_384";
        else if (queryEmbedding.length == 1024)
            vectorColumn = "embedding_1024";

        // Perform vector similarity search
        List<ChunkHitDto> similarChunks = chunkRepository.findSimilarChunks(projectId, vectorString, limit,
                vectorColumn,
                filePaths);

        log.info("[VectorSearchService] Found {} similar chunks (model={}, dims={}, col={})",
                similarChunks.size(), embedModel, queryEmbedding.length, vectorColumn);

        return similarChunks.stream()
                .map(hit -> new SearchResult(
                        hit.filePath(),
                        hit.chunkIndex(),
                        hit.content(),
                        hit.projectId()))
                .toList();
    }

    /**
     * Search across multiple projects.
     */
    public List<SearchResult> searchAcrossProjects(List<UUID> projectIds, String query, int topK, String embedModel) {
        return searchAcrossProjects(projectIds, query, topK, embedModel, null);
    }

    /**
     * Search across multiple projects with optional file filters.
     */
    public List<SearchResult> searchAcrossProjects(List<UUID> projectIds, String query, int topK, String embedModel,
            List<String> filePaths) {
        if (projectIds == null || projectIds.isEmpty() || query == null || query.isBlank()) {
            log.warn("[VectorSearchService] Empty query or project list");
            return List.of();
        }

        int limit = topK > 0 ? topK : DEFAULT_TOP_K;

        log.info("[VectorSearchService] Searching {} projects with query: '{}'", projectIds.size(),
                query.length() > 50 ? query.substring(0, 50) + "..." : query);

        // Generate embedding for query
        float[] queryEmbedding = embeddingService.generateEmbedding(query, embedModel);
        String vectorString = EmbeddingService.toVectorString(queryEmbedding);

        // Determine vector column
        String vectorColumn = "embedding_768"; // default
        if (queryEmbedding.length == 384)
            vectorColumn = "embedding_384";
        else if (queryEmbedding.length == 1024)
            vectorColumn = "embedding_1024";

        // Perform vector similarity search
        UUID[] projectIdsArray = projectIds.toArray(new UUID[0]);
        List<ChunkHitDto> similarChunks = chunkRepository.findSimilarChunksAcrossProjects(
                projectIdsArray, vectorString, limit, vectorColumn, filePaths);

        log.info("[VectorSearchService] Found {} similar chunks across projects", similarChunks.size());

        return similarChunks.stream()
                .map(hit -> new SearchResult(
                        hit.filePath(),
                        hit.chunkIndex(),
                        hit.content(),
                        hit.projectId()))
                .toList();
    }

    /**
     * Build context string from search results for RAG.
     */
    public String buildContextFromResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("=== Relevant Code Context ===\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            context.append(String.format("[%d] %s (chunk %d):\n", i + 1, result.filePath(), result.chunkIndex()));
            context.append("```\n");
            context.append(result.content());
            context.append("\n```\n\n");
        }

        return context.toString();
    }
}
