package com.ai.deepcode.controller;

import com.ai.deepcode.entity.Project;
import com.ai.deepcode.repository.ProjectRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "http://localhost:4200", allowCredentials = "true")
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Project> getProject(@PathVariable UUID id) {
        return projectRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Create or get existing project (idempotent-ish)
    @PostMapping
    public Project createOrGetProject(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String displayName = (String) payload.getOrDefault("displayName", name);
        String source = (String) payload.get("source"); // device, github

        // For GitHub
        String owner = (String) payload.get("owner");
        String repo = (String) payload.get("repo");
        String branch = (String) payload.get("branch");

        if ("github".equals(source) && owner != null && repo != null) {
            Optional<Project> existing = projectRepository.findByGithubOwnerAndGithubRepoAndGithubBranch(owner, repo,
                    branch);
            if (existing.isPresent()) {
                return existing.get();
            }
        } else if ("device".equals(source)) {
            Optional<Project> existing = projectRepository.findByName(name);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Create new
        Project p = new Project();
        p.setId(UUID.randomUUID()); // Must set ID manually since @GeneratedValue was removed
        p.setName(name);
        p.setDisplayName(displayName);
        p.setSource(source);
        p.setGithubOwner(owner);
        p.setGithubRepo(repo);
        p.setGithubBranch(branch);

        return projectRepository.save(p);
    }
}
