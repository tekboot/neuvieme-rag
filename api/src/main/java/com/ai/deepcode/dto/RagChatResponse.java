package com.ai.deepcode.dto;

import java.util.List;

public record RagChatResponse(
        String answer,
        RagMetadata rag) {
    public record RagMetadata(
            String strategyUsed,
            boolean usedExisting,
            boolean indexedNow,
            int chunksUsed,
            int chunksCreated,
            int filesIndexed,
            List<String> messageLog) {
    }
}
