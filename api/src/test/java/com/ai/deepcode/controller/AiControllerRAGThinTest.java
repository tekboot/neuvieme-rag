package com.ai.deepcode.controller;

import com.ai.deepcode.dto.*;
import com.ai.deepcode.entity.*;
import com.ai.deepcode.service.*;
import com.ai.deepcode.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AiControllerRAGThinTest {

    @Mock
    private OllamaService ollamaService;
    @Mock
    private WorkspaceStore workspaceStore;
    @Mock
    private FileContentService fileContentService;
    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private IndexingService indexingService;
    @Mock
    private IndexStatusRepository indexStatusRepository;
    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ChunkRepository chunkRepository;

    @InjectMocks
    private AiController aiController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testChatWithRag_ReindexSelected_Success() {
        // Arrange
        UUID projectId = UUID.randomUUID();
        RagFileRef fileRef = new RagFileRef("github", "src/main/App.java",
                new GithubRef("owner", "repo", "main", null));
        RagChatRequest request = new RagChatRequest(
                "What does this app do?",
                List.of(projectId),
                "qwen2.5-coder",
                "nomic-embed-text",
                5,
                "reindex",
                "selected",
                List.of(fileRef),
                1000,
                200);

        when(fileContentService.fetchContent(any())).thenReturn("public class App {}");
        when(vectorSearchService.searchAcrossProjects(any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(new VectorSearchService.SearchResult("src/main/App.java", 0, "public class App {}",
                        projectId)));
        when(vectorSearchService.buildContextFromResults(any())).thenReturn("Context from App.java");
        when(ollamaService.generate(any(), any())).thenReturn("This app has an App class.");

        // Act
        RagChatResponse response = aiController.chatWithRag(request);

        // Assert
        assertNotNull(response);
        assertEquals("This app has an App class.", response.answer());
        assertNotNull(response.rag());
        assertFalse(response.rag().usedExisting());
        assertTrue(response.rag().messageLog().contains("Triggering automatic indexing..."));

        verify(indexingService).indexProject(eq(projectId), anyMap(), anyString(), anyInt(), anyInt());
        verify(fileContentService).fetchContent(any());
    }

    @Test
    void testChatWithRag_ReindexAll_HealingFallback() {
        // Arrange
        UUID projectId = UUID.randomUUID();
        RagChatRequest request = new RagChatRequest(
                "query",
                List.of(projectId),
                "qwen2.5-coder",
                "nomic-embed-text",
                5,
                "reindex",
                "all",
                Collections.emptyList(),
                1000,
                200);

        Project project = new Project();
        project.setId(projectId);
        project.setSource("github");
        project.setGithubOwner("owner");
        project.setGithubRepo("repo");
        project.setGithubBranch("main");

        // Mock empty metadata but existing chunks
        when(projectFileRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(chunkRepository.findDistinctFilePathsByProjectId(projectId)).thenReturn(List.of("src/App.java"));
        when(fileContentService.fetchContent(any())).thenReturn("content");
        when(vectorSearchService.searchAcrossProjects(any(), any(), anyInt(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(vectorSearchService.buildContextFromResults(any())).thenReturn("");
        when(ollamaService.generate(any(), any())).thenReturn("Answer");

        // Act
        RagChatResponse response = aiController.chatWithRag(request);

        // Assert
        assertNotNull(response);
        assertTrue(response.rag().messageLog().stream().anyMatch(l -> l.contains("Recovered 1 paths")));
        verify(indexingService).indexProject(eq(projectId), argThat(map -> map.containsKey("src/App.java")),
                anyString(), anyInt(), anyInt());
    }

    @Test
    void testChatWithRag_MissingFiles_ThrowsBadRequest() {
        // Arrange
        UUID projectId = UUID.randomUUID();
        RagChatRequest request = new RagChatRequest(
                "query",
                List.of(projectId),
                null, null, null,
                "reindex",
                "selected",
                null, // Missing files
                null, null);

        // Act & Assert
        assertThrows(Exception.class, () -> aiController.chatWithRag(request));
    }
}
