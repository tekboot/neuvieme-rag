package com.ai.deepcode.repository;

import com.ai.deepcode.entity.ProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectFileRepository extends JpaRepository<ProjectFile, UUID> {
    List<ProjectFile> findByProjectId(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
