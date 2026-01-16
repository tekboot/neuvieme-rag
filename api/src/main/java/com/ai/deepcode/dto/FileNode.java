package com.ai.deepcode.dto;

import java.util.List;

public record FileNode(
        String id,
        String name,
        String type,     // "folder" | "file"
        String path,
        List<FileNode> children,
        Boolean expanded
) {}
