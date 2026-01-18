package com.ai.deepcode.repository;

import com.ai.deepcode.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByName(String name);

    List<Project> findBySource(String source);

    Optional<Project> findByGithubOwnerAndGithubRepoAndGithubBranch(
        String githubOwner, String githubRepo, String githubBranch);
}
