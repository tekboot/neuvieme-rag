package com.ai.deepcode.repository;

import com.ai.deepcode.dto.ChunkHitDto;
import com.ai.deepcode.entity.Project;
import com.ai.deepcode.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class VectorSearchIntegrationThinTest {

    @Autowired
    private CustomChunkRepository chunkRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testIndexingAndSearch() {
        // 1) Setup a dummy project (must exist before inserting chunks due to FK)
        UUID projectId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setName("Test Project");
        project.setDisplayName("Test Project");
        project.setSource("device");
        projectRepository.saveAndFlush(project); // IMPORTANT: flush so the row exists in DB now

        // 2) Insert a chunk with an embedding using JdbcTemplate (bypassing JPA mapping)
        // nomic-embed-text => 768 dims
        float[] mockEmbedding = new float[768];
        mockEmbedding[0] = 1.0f;
        String vectorString = EmbeddingService.toVectorString(mockEmbedding);

        jdbcTemplate.update(
                "INSERT INTO chunks (id, project_id, file_path, chunk_index, content, embedding_768) " +
                        "VALUES (?, ?, ?, ?, ?, cast(? as vector))",
                UUID.randomUUID(), projectId, "test.txt", 0, "Hello world", vectorString
        );

        // 3) Search for it
        List<ChunkHitDto> results = chunkRepository.findSimilarChunks(
                projectId, vectorString, 5, "embedding_768", null
        );

        // 4) Verify
        assertFalse(results.isEmpty(), "Should find the inserted chunk");
        assertEquals("test.txt", results.get(0).filePath());
        assertTrue(results.get(0).score() > 0.99, "Similarity score should be near 1.0");
    }
}