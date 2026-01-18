package com.ai.deepcode.config;

import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson configuration:
 * - Handles deeply nested JSON structures (large file trees)
 * - Supports Java 8 date/time types (OffsetDateTime, etc.)
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register Java 8 date/time module for OffsetDateTime serialization
        mapper.registerModule(new JavaTimeModule());

        // Serialize dates as ISO-8601 strings, not timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Increase max nesting depth for deeply nested file trees
        mapper.getFactory().setStreamWriteConstraints(
            StreamWriteConstraints.builder()
                .maxNestingDepth(5000)
                .build()
        );

        return mapper;
    }
}
