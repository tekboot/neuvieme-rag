package com.ai.deepcode.controller;

import com.ai.deepcode.service.WorkspaceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
@CrossOrigin(
        origins = "http://localhost:4200",
        allowCredentials = "true"
)
public class WorkspaceController {

    private final WorkspaceStore workspaceStore;

    public WorkspaceController(WorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
    }

    @PostMapping("/device/upload")
    public ResponseEntity<Map<String, Object>> uploadDeviceFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("paths") String[] paths
    ) {
        if (files.length != paths.length) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "files and paths arrays must have the same length"));
        }

        int uploaded = 0;
        for (int i = 0; i < files.length; i++) {
            try {
                byte[] content = files[i].getBytes();
                workspaceStore.storeFile(paths[i], content);
                uploaded++;
            } catch (IOException e) {
                // Skip files that fail to read
            }
        }

        return ResponseEntity.ok(Map.of("uploaded", uploaded));
    }

    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> listFiles() {
        return ResponseEntity.ok(Map.of(
                "count", workspaceStore.getFileCount(),
                "paths", workspaceStore.getAllPaths()
        ));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearWorkspace() {
        workspaceStore.clear();
        return ResponseEntity.ok(Map.of("cleared", true));
    }
}
