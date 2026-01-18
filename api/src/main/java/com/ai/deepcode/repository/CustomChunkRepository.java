package com.ai.deepcode.repository;

import com.ai.deepcode.dto.ChunkHitDto;
import java.util.List;
import java.util.UUID;

public interface CustomChunkRepository {
        List<ChunkHitDto> findSimilarChunks(UUID projectId, String embedding, int limit, String vectorColumn,
                        List<String> filePaths);

        List<ChunkHitDto> findSimilarChunksAcrossProjects(UUID[] projectIds, String embedding, int limit,
                        String vectorColumn,
                        List<String> filePaths);
}
