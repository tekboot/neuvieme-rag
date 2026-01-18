package com.ai.deepcode.repository;

import com.ai.deepcode.dto.ChunkHitDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class CustomChunkRepositoryImpl implements CustomChunkRepository {

        @PersistenceContext
        private EntityManager entityManager;

        @Override
        @SuppressWarnings("unchecked")
        public List<ChunkHitDto> findSimilarChunks(UUID projectId, String embedding, int limit, String vectorColumn,
                        List<String> filePaths) {

                String scoreExpr = String.format("1 - (c.%s <=> cast(:embedding as vector))", vectorColumn);

                StringBuilder sql = new StringBuilder(String.format("""
                                SELECT c.id, c.project_id, c.file_path, c.chunk_index, c.content, %s as score
                                FROM chunks c
                                WHERE c.project_id = :projectId
                                AND c.%s IS NOT NULL
                                """, scoreExpr, vectorColumn));

                if (filePaths != null && !filePaths.isEmpty()) {
                        sql.append(" AND c.file_path IN (:filePaths)\n");
                }

                sql.append(String.format(" ORDER BY c.%s <=> cast(:embedding as vector)\n", vectorColumn));
                sql.append(" LIMIT :limit");

                var query = entityManager.createNativeQuery(sql.toString(), "ChunkHitMapping")
                                .setParameter("projectId", projectId)
                                .setParameter("embedding", embedding)
                                .setParameter("limit", limit);

                if (filePaths != null && !filePaths.isEmpty()) {
                        query.setParameter("filePaths", filePaths);
                }

                return (List<ChunkHitDto>) query.getResultList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<ChunkHitDto> findSimilarChunksAcrossProjects(UUID[] projectIds, String embedding, int limit,
                        String vectorColumn, List<String> filePaths) {

                String scoreExpr = String.format("1 - (c.%s <=> cast(:embedding as vector))", vectorColumn);

                StringBuilder sql = new StringBuilder(String.format("""
                                SELECT c.id, c.project_id, c.file_path, c.chunk_index, c.content, %s as score
                                FROM chunks c
                                WHERE c.project_id = ANY(cast(:projectIds as uuid[]))
                                AND c.%s IS NOT NULL
                                """, scoreExpr, vectorColumn));

                if (filePaths != null && !filePaths.isEmpty()) {
                        sql.append(" AND c.file_path IN (:filePaths)\n");
                }

                sql.append(String.format(" ORDER BY c.%s <=> cast(:embedding as vector)\n", vectorColumn));
                sql.append(" LIMIT :limit");

                var query = entityManager.createNativeQuery(sql.toString(), "ChunkHitMapping")
                                .setParameter("projectIds", projectIds)
                                .setParameter("embedding", embedding)
                                .setParameter("limit", limit);

                if (filePaths != null && !filePaths.isEmpty()) {
                        query.setParameter("filePaths", filePaths);
                }

                return (List<ChunkHitDto>) query.getResultList();
        }
}
