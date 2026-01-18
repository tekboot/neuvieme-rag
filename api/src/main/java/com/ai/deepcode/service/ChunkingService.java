package com.ai.deepcode.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for splitting text content into overlapping chunks.
 * Uses character-based chunking with configurable size and overlap.
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    public record ChunkResult(String content, int index, int tokenEstimate) {}

    /**
     * Split content into overlapping chunks.
     *
     * @param content The text content to chunk
     * @param chunkSize Target chunk size in characters
     * @param overlap Number of overlapping characters between chunks
     * @return List of ChunkResult containing the chunked content
     */
    public List<ChunkResult> chunkContent(String content, int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (overlap < 0 || overlap >= chunkSize) {
            overlap = (int) (chunkSize * 0.1); // Default 10% overlap
        }

        List<ChunkResult> chunks = new ArrayList<>();
        int contentLength = content.length();

        if (contentLength <= chunkSize) {
            // Content fits in single chunk
            chunks.add(new ChunkResult(content, 0, estimateTokens(content)));
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;
        int step = chunkSize - overlap;

        while (start < contentLength) {
            int end = Math.min(start + chunkSize, contentLength);
            String chunkContent = content.substring(start, end);

            // Try to break at a natural boundary (newline or space) if not at end
            if (end < contentLength) {
                int lastNewline = chunkContent.lastIndexOf('\n');
                int lastSpace = chunkContent.lastIndexOf(' ');

                // Prefer newline break if it's in the latter half of the chunk
                if (lastNewline > chunkSize / 2) {
                    chunkContent = chunkContent.substring(0, lastNewline + 1);
                } else if (lastSpace > chunkSize / 2) {
                    chunkContent = chunkContent.substring(0, lastSpace + 1);
                }
            }

            chunks.add(new ChunkResult(
                chunkContent.trim(),
                chunkIndex,
                estimateTokens(chunkContent)
            ));

            start += step;
            chunkIndex++;

            // Safety check to prevent infinite loops
            if (chunkIndex > 10000) {
                log.warn("[ChunkingService] Too many chunks, stopping at 10000");
                break;
            }
        }

        log.debug("[ChunkingService] Split {} chars into {} chunks (size={}, overlap={})",
            contentLength, chunks.size(), chunkSize, overlap);

        return chunks;
    }

    /**
     * Chunk file content with file path prepended for context.
     */
    public List<ChunkResult> chunkFileContent(String filePath, String content, int chunkSize, int overlap) {
        // Prepend file path as context
        String header = "File: " + filePath + "\n---\n";
        String fullContent = header + content;

        return chunkContent(fullContent, chunkSize, overlap);
    }

    /**
     * Rough token estimate (approximately 4 characters per token for English text).
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }
}
