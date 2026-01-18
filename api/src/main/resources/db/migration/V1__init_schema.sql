-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Projects table: stores imported projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    source VARCHAR(50) NOT NULL CHECK (source IN ('device', 'github')),
    github_owner VARCHAR(255),
    github_repo VARCHAR(255),
    github_branch VARCHAR(255),
    file_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index status table: tracks indexing progress per project
CREATE TABLE index_status (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'not_started' CHECK (status IN ('not_started', 'in_progress', 'completed', 'error')),
    total_files INT DEFAULT 0,
    indexed_files INT DEFAULT 0,
    total_chunks INT DEFAULT 0,
    embed_model VARCHAR(100),
    chunk_size INT,
    chunk_overlap INT,
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(project_id)
);

-- Chunks table: stores document chunks with embeddings
CREATE TABLE chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    file_path VARCHAR(1024) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(768), -- nomic-embed-text produces 768 dimensions
    token_count INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for vector similarity search using HNSW (faster for large datasets)
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);

-- Index for filtering by project
CREATE INDEX idx_chunks_project_id ON chunks(project_id);

-- Index for file path lookups
CREATE INDEX idx_chunks_file_path ON chunks(file_path);

-- Composite index for project + file path
CREATE INDEX idx_chunks_project_file ON chunks(project_id, file_path);
