-- Add columns for other embedding dimensions
ALTER TABLE chunks ADD COLUMN embedding_384 vector(384);
ALTER TABLE chunks ADD COLUMN embedding_1024 vector(1024);

-- Rename existing 768 column to be explicit (optional, but good for clarity)
-- We will keep 'embedding' as the column name for 768 in the entity for backward compat if needed,
-- OR we rename it here. Let's rename it to enforce clarity.
ALTER TABLE chunks RENAME COLUMN embedding TO embedding_768;

-- Create indices for new columns
CREATE INDEX idx_chunks_embedding_384 ON chunks USING hnsw (embedding_384 vector_cosine_ops);
CREATE INDEX idx_chunks_embedding_1024 ON chunks USING hnsw (embedding_1024 vector_cosine_ops);

-- Rename old index to match
ALTER INDEX idx_chunks_embedding RENAME TO idx_chunks_embedding_768;
