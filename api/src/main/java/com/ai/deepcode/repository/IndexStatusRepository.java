package com.ai.deepcode.repository;

import com.ai.deepcode.entity.IndexStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IndexStatusRepository extends JpaRepository<IndexStatus, UUID> {

    Optional<IndexStatus> findByProjectId(UUID projectId);
}
