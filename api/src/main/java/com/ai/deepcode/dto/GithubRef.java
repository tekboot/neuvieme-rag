package com.ai.deepcode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRef(
        String owner,
        String repo,
        String branch,
        String subPath) {
}
