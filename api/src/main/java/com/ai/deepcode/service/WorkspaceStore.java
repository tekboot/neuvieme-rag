package com.ai.deepcode.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceStore {

    private final ConcurrentHashMap<String, byte[]> deviceFiles = new ConcurrentHashMap<>();

    public void storeFile(String path, byte[] content) {
        deviceFiles.put(path, content);
    }

    public byte[] getFile(String path) {
        return deviceFiles.get(path);
    }

    public String getFileAsString(String path) {
        byte[] bytes = deviceFiles.get(path);
        if (bytes == null) return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean hasFile(String path) {
        return deviceFiles.containsKey(path);
    }

    public Collection<String> getAllPaths() {
        return deviceFiles.keySet();
    }

    public int getFileCount() {
        return deviceFiles.size();
    }

    public void clear() {
        deviceFiles.clear();
    }
}
