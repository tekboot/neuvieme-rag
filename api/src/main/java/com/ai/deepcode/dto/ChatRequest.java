package com.ai.deepcode.dto;

import java.util.List;

public record ChatRequest(String message, Context context, String model) {

    public record Context(String mode, List<ContextFile> files) {}

    public record ContextFile(
            String source,  // "device" or "github"
            String path,
            GithubRef github
    ) {}

    public record GithubRef(
            String owner,
            String repo,
            String branch,
            String subPath
    ) {}
}
