package com.ai.deepcode.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for model activation states.
 * Tracks which models are active/inactive and their installation status.
 */
@Service
public class ModelStateStore {

    private static final Logger log = LoggerFactory.getLogger(ModelStateStore.class);

    public enum ModelStatus {
        INSTALLED,      // Model is installed and ready
        INSTALLING,     // Model is being pulled/installed
        ACTIVE,         // Model is installed and activated for use
        INACTIVE,       // Model is installed but deactivated
        ERROR           // Installation or operation failed
    }

    // Model name -> status
    private final Map<String, ModelStatus> modelStates = new ConcurrentHashMap<>();

    // Models currently being pulled (name -> progress message)
    private final Map<String, String> pullingProgress = new ConcurrentHashMap<>();

    // Set of activated models (subset of installed)
    private final Set<String> activatedModels = ConcurrentHashMap.newKeySet();

    public ModelStateStore() {
        // Default activated model
        activatedModels.add("qwen2.5-coder:7b");
    }

    public ModelStatus getStatus(String modelName) {
        if (pullingProgress.containsKey(modelName)) {
            return ModelStatus.INSTALLING;
        }
        if (activatedModels.contains(modelName)) {
            return ModelStatus.ACTIVE;
        }
        return modelStates.getOrDefault(modelName, ModelStatus.INSTALLED);
    }

    public void setInstalled(String modelName) {
        modelStates.put(modelName, ModelStatus.INSTALLED);
        pullingProgress.remove(modelName);
        log.info("[ModelStateStore] Model '{}' marked as INSTALLED", modelName);
    }

    public void setInstalling(String modelName, String progress) {
        pullingProgress.put(modelName, progress);
        log.info("[ModelStateStore] Model '{}' INSTALLING: {}", modelName, progress);
    }

    public void setError(String modelName, String error) {
        modelStates.put(modelName, ModelStatus.ERROR);
        pullingProgress.remove(modelName);
        log.error("[ModelStateStore] Model '{}' ERROR: {}", modelName, error);
    }

    public void activate(String modelName) {
        activatedModels.add(modelName);
        log.info("[ModelStateStore] Model '{}' ACTIVATED", modelName);
    }

    public void deactivate(String modelName) {
        activatedModels.remove(modelName);
        log.info("[ModelStateStore] Model '{}' DEACTIVATED", modelName);
    }

    public boolean isActive(String modelName) {
        return activatedModels.contains(modelName);
    }

    public Set<String> getActivatedModels() {
        return Set.copyOf(activatedModels);
    }

    public String getPullProgress(String modelName) {
        return pullingProgress.get(modelName);
    }

    public boolean isPulling(String modelName) {
        return pullingProgress.containsKey(modelName);
    }
}
