package com.ai.deepcode.entity;

/**
 * Enum for indexing status values.
 * Aligned with database CHECK constraint in index_status table.
 */
public enum IndexingStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
