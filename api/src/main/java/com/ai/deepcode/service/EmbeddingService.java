package com.ai.deepcode.service;

import com.ai.deepcode.exception.OllamaUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Service for generating embeddings using Ollama's embedding models.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String DEFAULT_EMBED_MODEL = "nomic-embed-text";

    private final WebClient webClient;

    public EmbeddingService(@Value("${ollama.base-url}") String ollamaBaseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    /**
     * Generate embedding for a single text using the default model.
     */
    public float[] generateEmbedding(String text) {
        return generateEmbedding(text, DEFAULT_EMBED_MODEL);
    }

    /**
     * Generate embedding for a single text using the specified model.
     */
    public float[] generateEmbedding(String text, String model) {
        if (text == null || text.isBlank()) {
            log.warn("[EmbeddingService] Empty text provided, returning empty embedding");
            return new float[768]; // Return zero vector
        }

        String effectiveModel = (model != null && !model.isBlank()) ? model : DEFAULT_EMBED_MODEL;

        try {
            Map<String, Object> request = Map.of(
                    "model", effectiveModel,
                    "prompt", text);

            EmbeddingResponse response = webClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .block();

            if (response != null && response.embedding() != null) {
                log.debug("[EmbeddingService] Generated embedding with {} dimensions using {}",
                        response.embedding().length, effectiveModel);
                return response.embedding();
            }

            log.error("[EmbeddingService] Empty response from Ollama");
            return new float[768];

        } catch (WebClientRequestException e) {
            log.error("[EmbeddingService] Failed to connect to Ollama: {}", e.getMessage());
            throw new OllamaUnavailableException("Ollama is not running or not accessible at the configured URL", e);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.error("[EmbeddingService] Ollama embeddings endpoint not found (404)");
                throw new OllamaUnavailableException(
                        "Ollama embeddings endpoint is not available (404). Ensure you are using a version of Ollama that supports embeddings.",
                        e);
            }
            log.error("[EmbeddingService] Ollama returned error: status={}, body={}", e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new RuntimeException("Ollama returned an error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[EmbeddingService] Failed to generate embedding: {}", e.getMessage());
            throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
        }
    }

    /**
     * Generate embeddings for multiple texts in batch.
     */
    public List<float[]> generateEmbeddings(List<String> texts, String model) {
        return texts.stream()
                .map(text -> generateEmbedding(text, model))
                .toList();
    }

    /**
     * Convert float array to PostgreSQL vector string format.
     * Format: [0.1,0.2,0.3,...]
     */
    public static String toVectorString(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0)
                sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Response record for Ollama embeddings API.
     */
    private record EmbeddingResponse(float[] embedding) {
    }
}
