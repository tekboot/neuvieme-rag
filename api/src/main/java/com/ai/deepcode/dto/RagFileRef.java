package com.ai.deepcode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RagFileRef(
        String source,
        String path,
        GithubRef github) {
}
