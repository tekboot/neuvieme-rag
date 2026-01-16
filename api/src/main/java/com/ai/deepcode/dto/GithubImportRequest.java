package com.ai.deepcode.dto;

public record GithubImportRequest(
        String owner,
        String repo,
        String branch,
        String subPath
) {}
