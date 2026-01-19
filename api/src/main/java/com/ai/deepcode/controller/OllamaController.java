package com.ai.deepcode.controller;

import com.ai.deepcode.dto.OllamaModelDto;
import com.ai.deepcode.exception.OllamaUnavailableException;
import com.ai.deepcode.service.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Ollama model management.
 * Provides endpoints to list and pull models from Ollama.
 */
@RestController
@RequestMapping("/api/ollama")
public class OllamaController {

    private static final Logger log = LoggerFactory.getLogger(OllamaController.class);

    private final OllamaClient ollamaClient;

    public OllamaController(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    /**
     * GET /api/ollama/models
     * Returns list of installed Ollama models with metadata.
     */
    @GetMapping("/models")
    public ResponseEntity<?> getModels() {
        log.info("[OllamaController] GET /api/ollama/models");

        try {
            List<OllamaModelDto> models = ollamaClient.listModels();

            // Transform to frontend-friendly format
            List<Map<String, String>> response = models.stream()
                    .map(model -> Map.of(
                            "name", model.name(),
                            "size", formatSize(model.size()),
                            "modifiedAt", model.modified_at() != null ? model.modified_at() : "",
                            "digest", model.digest() != null ? model.digest() : ""))
                    .toList();

            return ResponseEntity.ok(response);

        } catch (OllamaUnavailableException e) {
            throw e; // Let GlobalExceptionHandler handle it
        } catch (Exception e) {
            log.error("[OllamaController] Unexpected error: {}", e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "INTERNAL_ERROR",
                            "message", "Failed to fetch models: " + e.getMessage()));
        }
    }

    /**
     * POST /api/ollama/pull
     * Triggers installation of a new model.
     * Request body: { "model": "model-name" }
     */
    @PostMapping("/pull")
    public ResponseEntity<?> pullModel(@RequestBody Map<String, String> request) {
        String modelName = request.get("model");

        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "success", false,
                            "error", "Model name is required"));
        }

        log.info("[OllamaController] POST /api/ollama/pull - model={}", modelName);

        try {
            ollamaClient.pullModel(modelName);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Model '" + modelName + "' installed successfully"));

        } catch (OllamaUnavailableException e) {
            throw e; // Let GlobalExceptionHandler handle it
        } catch (Exception e) {
            log.error("[OllamaController] Unexpected error during pull: {}", e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "INTERNAL_ERROR",
                            "message", "Failed to pull model: " + e.getMessage()));
        }
    }

    /**
     * Format byte size to human-readable string
     */
    private String formatSize(Long bytes) {
        if (bytes == null || bytes == 0)
            return "unknown";

        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024)
            return (bytes / (1024 * 1024)) + " MB";

        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
