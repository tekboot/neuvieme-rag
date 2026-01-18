package com.ai.deepcode.exception;

/**
 * Custom exception for when Ollama is not available or reachable.
 */
public class OllamaUnavailableException extends RuntimeException {
    public OllamaUnavailableException(String message) {
        super(message);
    }

    public OllamaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
