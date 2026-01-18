package com.ai.deepcode.service;

import com.ai.deepcode.dto.ModelInfo;
import com.ai.deepcode.dto.PullResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for managing Ollama models - list, pull, etc.
 */
@Service
public class OllamaModelService {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelService.class);

    private final WebClient webClient;
    private final ModelStateStore stateStore;

    public OllamaModelService(
            WebClient ollamaWebClient,
            ModelStateStore stateStore) {
        this.webClient = ollamaWebClient;
        this.stateStore = stateStore;
    }

    /**
     * List all installed models from Ollama.
     */
    @SuppressWarnings("unchecked")
    public List<ModelInfo> listModels() {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !response.containsKey("models")) {
                log.warn("[OllamaModelService] No models found in response");
                return List.of();
            }

            List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("models");
            List<ModelInfo> result = new ArrayList<>();

            for (Map<String, Object> model : models) {
                String name = (String) model.get("name");
                String size = formatSize(model.get("size"));
                String modifiedAt = (String) model.get("modified_at");

                ModelStateStore.ModelStatus status = stateStore.getStatus(name);
                boolean active = stateStore.isActive(name);

                result.add(new ModelInfo(
                        name,
                        size,
                        modifiedAt,
                        status.name().toLowerCase(),
                        active));
            }

            log.info("[OllamaModelService] Listed {} models", result.size());
            return result;

        } catch (Exception e) {
            log.error("[OllamaModelService] Failed to list models: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Pull/install a model from Ollama registry.
     * This is a blocking operation that can take a while.
     */
    public PullResult pullModel(String modelName) {
        log.info("[OllamaModelService] Starting pull for model: {}", modelName);
        stateStore.setInstalling(modelName, "Starting download...");

        try {
            // Ollama pull is typically streaming, but we'll use a simple blocking call
            Map<String, Object> request = Map.of("name", modelName, "stream", false);

            Map<String, Object> response = webClient.post()
                    .uri("/api/pull")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            stateStore.setInstalled(modelName);
            log.info("[OllamaModelService] Successfully pulled model: {}", modelName);

            return new PullResult(true, "Model '" + modelName + "' installed successfully", null);

        } catch (Exception e) {
            String error = e.getMessage();
            stateStore.setError(modelName, error);
            log.error("[OllamaModelService] Failed to pull model '{}': {}", modelName, error);

            return new PullResult(false, null, "Failed to pull model: " + error);
        }
    }

    /**
     * Activate a model for use in chat.
     */
    public void activateModel(String modelName) {
        stateStore.activate(modelName);
    }

    /**
     * Deactivate a model.
     */
    public void deactivateModel(String modelName) {
        stateStore.deactivate(modelName);
    }

    /**
     * Get list of active models that can be used for chat.
     */
    public List<ModelInfo> getActiveModels() {
        List<ModelInfo> all = listModels();
        return all.stream()
                .filter(ModelInfo::active)
                .toList();
    }

    private String formatSize(Object sizeObj) {
        if (sizeObj == null)
            return "unknown";
        if (sizeObj instanceof Number) {
            long bytes = ((Number) sizeObj).longValue();
            if (bytes < 1024)
                return bytes + " B";
            if (bytes < 1024 * 1024)
                return (bytes / 1024) + " KB";
            if (bytes < 1024 * 1024 * 1024)
                return (bytes / (1024 * 1024)) + " MB";
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return sizeObj.toString();
    }
}
