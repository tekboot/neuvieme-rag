package com.ai.deepcode.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateFileRequest(
        @NotBlank String owner,
        @NotBlank String repo,
        @NotBlank String branch,
        @NotBlank String path,
        @NotBlank String commitMessage,
        @NotBlank String newContentUtf8
) {}
