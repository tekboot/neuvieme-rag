-- Migration to persist file tree structure for projects
CREATE TABLE project_files (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    path VARCHAR(1024) NOT NULL,
    source VARCHAR(50) NOT NULL, -- 'device' or 'github'
    github_owner VARCHAR(255),
    github_repo VARCHAR(255),
    github_branch VARCHAR(255),
    github_sub_path VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, path)
);

CREATE INDEX idx_project_files_project_id ON project_files(project_id);
