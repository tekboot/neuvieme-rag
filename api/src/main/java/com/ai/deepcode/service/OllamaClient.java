package com.ai.deepcode.service;

import com.ai.deepcode.dto.OllamaModelDto;
import com.ai.deepcode.dto.OllamaPullRequest;
import com.ai.deepcode.dto.OllamaPullResponse;
import com.ai.deepcode.dto.OllamaTagsResponse;
import com.ai.deepcode.exception.OllamaUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * Client service for interacting with Ollama HTTP API.
 * Handles model listing and pulling operations.
 */
@Service
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(5);

    private final WebClient webClient;

    public OllamaClient(WebClient ollamaWebClient) {
        this.webClient = ollamaWebClient;
    }

    /**
     * List all installed models from Ollama.
     * 
     * @return List of installed models
     * @throws OllamaUnavailableException if Ollama is not running
     */
    public List<OllamaModelDto> listModels() {
        try {
            log.info("[OllamaClient] Fetching models from /api/tags");

            OllamaTagsResponse response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(OllamaTagsResponse.class)
                    .timeout(LIST_TIMEOUT)
                    .block();

            if (response == null || response.models() == null) {
                log.warn("[OllamaClient] No models found in response");
                return List.of();
            }

            log.info("[OllamaClient] Successfully fetched {} models", response.models().size());
            return response.models();

        } catch (WebClientRequestException e) {
            log.error("[OllamaClient] Failed to connect to Ollama: {}", e.getMessage());
            throw new OllamaUnavailableException("Ollama is not running or not accessible at the configured URL");
        } catch (WebClientResponseException e) {
            log.error("[OllamaClient] Ollama returned error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new OllamaUnavailableException("Ollama returned an error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[OllamaClient] Unexpected error listing models: {}", e.getMessage());
            throw new OllamaUnavailableException("Failed to list models: " + e.getMessage());
        }
    }

    /**
     * Pull/install a model from Ollama registry.
     * This is a blocking operation that can take several minutes.
     * 
     * @param modelName Name of the model to pull (e.g., "llama3.2:1b")
     * @return Response from Ollama
     * @throws OllamaUnavailableException if Ollama is not running
     */
    public OllamaPullResponse pullModel(String modelName) {
        try {
            log.info("[OllamaClient] Starting pull for model: {}", modelName);

            OllamaPullRequest request = new OllamaPullRequest(modelName, false);

            OllamaPullResponse response = webClient.post()
                    .uri("/api/pull")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(OllamaPullResponse.class)
                    .timeout(PULL_TIMEOUT)
                    .block();

            log.info("[OllamaClient] Successfully pulled model: {}", modelName);
            return response != null ? response : new OllamaPullResponse("success", null);

        } catch (WebClientRequestException e) {
            log.error("[OllamaClient] Failed to connect to Ollama: {}", e.getMessage());
            throw new OllamaUnavailableException("Ollama is not running or not accessible at the configured URL");
        } catch (WebClientResponseException e) {
            log.error("[OllamaClient] Ollama returned error during pull: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new OllamaUnavailableException("Failed to pull model: " + e.getMessage());
        } catch (Exception e) {
            log.error("[OllamaClient] Unexpected error pulling model: {}", e.getMessage());
            throw new OllamaUnavailableException("Failed to pull model: " + e.getMessage());
        }
    }

}
