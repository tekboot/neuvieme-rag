package com.ai.deepcode.repository;

import com.ai.deepcode.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID>, CustomChunkRepository {

    List<Chunk> findByProjectId(UUID projectId);

    List<Chunk> findByProjectIdAndFilePath(UUID projectId, String filePath);

    long countByProjectId(UUID projectId);

    @Modifying
    @Query("DELETE FROM Chunk c WHERE c.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);

    /**
     * Vector similarity search using pgvector's cosine distance.
     * Returns chunks ordered by similarity (closest first).
     *
     * Note: The embedding parameter is cast to vector type in the query.
     */
    // Custom queries are in CustomChunkRepositoryImpl
}
