package com.ai.deepcode.controller;

import com.ai.deepcode.dto.ModelInfo;
import com.ai.deepcode.dto.PullResult;
import com.ai.deepcode.service.OllamaModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin API for model management.
 * Temporarily public (no auth required).
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(
        origins = "http://localhost:4200",
        allowCredentials = "true"
)
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final OllamaModelService modelService;

    public AdminController(OllamaModelService modelService) {
        this.modelService = modelService;
    }

    /**
     * List all installed models with their status.
     */
    @GetMapping("/models")
    public ResponseEntity<List<ModelInfo>> listModels() {
        log.info("[AdminController] GET /api/admin/models");
        List<ModelInfo> models = modelService.listModels();
        return ResponseEntity.ok(models);
    }

    /**
     * Get only active models (for chat dropdown).
     */
    @GetMapping("/models/active")
    public ResponseEntity<List<ModelInfo>> getActiveModels() {
        log.info("[AdminController] GET /api/admin/models/active");
        List<ModelInfo> models = modelService.getActiveModels();
        return ResponseEntity.ok(models);
    }

    /**
     * Pull/install a new model.
     */
    @PostMapping("/models/pull")
    public ResponseEntity<PullResult> pullModel(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new PullResult(false, null, "Model name is required"));
        }

        log.info("[AdminController] POST /api/admin/models/pull - name={}", name);
        PullResult result = modelService.pullModel(name);

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Activate a model for use in chat.
     */
    @PostMapping("/models/activate")
    public ResponseEntity<Map<String, Object>> activateModel(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Model name is required"));
        }

        log.info("[AdminController] POST /api/admin/models/activate - name={}", name);
        modelService.activateModel(name);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Model '" + name + "' activated"
        ));
    }

    /**
     * Deactivate a model.
     */
    @PostMapping("/models/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateModel(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Model name is required"));
        }

        log.info("[AdminController] POST /api/admin/models/deactivate - name={}", name);
        modelService.deactivateModel(name);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Model '" + name + "' deactivated"
        ));
    }
}
