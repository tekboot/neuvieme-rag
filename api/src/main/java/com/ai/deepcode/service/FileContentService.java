package com.ai.deepcode.service;

import com.ai.deepcode.dto.RagFileRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FileContentService {

    private static final Logger log = LoggerFactory.getLogger(FileContentService.class);

    private final WorkspaceStore workspaceStore;
    private final GithubFileService githubFileService;

    // Binary extensions to skip
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "tar", "gz", "rar", "7z",
            "exe", "dll", "so", "dylib",
            "mp3", "mp4", "avi", "mov", "wav",
            "ttf", "otf", "woff", "woff2", "eot");

    public FileContentService(WorkspaceStore workspaceStore, GithubFileService githubFileService) {
        this.workspaceStore = workspaceStore;
        this.githubFileService = githubFileService;
    }

    /**
     * Fetch content for a file reference (uses security context for auth).
     */
    public String fetchContent(RagFileRef file) {
        return fetchContent(file, null);
    }

    /**
     * Fetch content for a file reference with explicit authentication.
     *
     * @param file File reference containing source, path, and optional github metadata
     * @param auth Authentication object for GitHub API calls (required for github source)
     * @return File content as string, or null if not eligible/available
     */
    public String fetchContent(RagFileRef file, Authentication auth) {
        if (file == null || file.path() == null)
            return null;

        if (!isTextEligible(file.path())) {
            log.warn("[FileContentService] Skipping non-text eligible file: {}", file.path());
            return null;
        }

        if ("github".equalsIgnoreCase(file.source())) {
            if (file.github() == null) {
                log.warn("[FileContentService] GitHub source missing metadata for {}", file.path());
                return null;
            }
            return githubFileService.getFileContent(
                    file.github().owner(),
                    file.github().repo(),
                    file.path(),
                    file.github().branch(),
                    file.github().subPath(),
                    auth);
        } else if ("device".equalsIgnoreCase(file.source())) {
            return workspaceStore.getFileAsString(file.path());
        }

        return null;
    }

    /**
     * Check if a file is eligible for text-based processing (indexing/context).
     */
    public boolean isTextEligible(String path) {
        if (path == null)
            return false;
        String fileName = path.toLowerCase();

        // Exact filename matches
        Set<String> validNames = Set.of("license", "dockerfile", "makefile", "procfile", "gemfile", "pipfile",
                "package.json", "requirements.txt");
        String justName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf("/") + 1) : fileName;
        if (validNames.contains(justName))
            return true;

        // Extension matches
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0)
            return false;
        String ext = fileName.substring(lastDot + 1);

        if (BINARY_EXTENSIONS.contains(ext))
            return false;

        List<String> validExtensions = List.of(
                "md", "txt", "java", "ts", "js", "json", "yml", "yaml", "xml",
                "properties", "env", "gitignore", "py", "c", "cpp", "h", "hpp",
                "cs", "sh", "bash", "sql", "css", "html", "kt", "rs", "go");

        return validExtensions.contains(ext);
    }
}
