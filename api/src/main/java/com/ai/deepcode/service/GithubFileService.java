package com.ai.deepcode.service;

import com.ai.deepcode.api.GithubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
public class GithubFileService {

    private static final Logger log = LoggerFactory.getLogger(GithubFileService.class);

    private final GithubTokenService tokenService;
    private final GithubApiClient githubClient;

    public GithubFileService(GithubTokenService tokenService, GithubApiClient githubClient) {
        this.tokenService = tokenService;
        this.githubClient = githubClient;
    }

    /**
     * Fetch file content from GitHub using the current user's OAuth token from security context.
     */
    public String getFileContent(String owner, String repo, String path, String branch, String subPath) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return getFileContent(owner, repo, path, branch, subPath, auth);
    }

    /**
     * Fetch file content from GitHub using the provided authentication.
     *
     * @param owner   Repository owner
     * @param repo    Repository name
     * @param path    File path within repo
     * @param branch  Branch/ref (can be null for default)
     * @param subPath Optional subPath prefix that was used during import
     * @param auth    Authentication object (must be OAuth2AuthenticationToken for GitHub)
     * @return File content as String
     * @throws ResponseStatusException with 401 if GitHub auth is missing or expired
     * @throws ResponseStatusException with 500 for other errors
     */
    public String getFileContent(String owner, String repo, String path, String branch, String subPath,
            Authentication auth) {
        log.info("[GithubFileService] === START getFileContent ===");
        log.info("[GithubFileService] Input: owner={}, repo={}, path={}, branch={}, subPath={}",
                owner, repo, path, branch, subPath);

        log.info("[GithubFileService] Authentication: present={}, type={}",
                auth != null,
                auth != null ? auth.getClass().getSimpleName() : "null");

        if (auth == null) {
            log.error("[GithubFileService] FAIL: No authentication context available");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "GITHUB_AUTH_REQUIRED: GitHub authentication required. Please connect your GitHub account.");
        }

        // Check if it's an OAuth2 token
        if (!(auth instanceof OAuth2AuthenticationToken)) {
            log.error("[GithubFileService] FAIL: Authentication is not OAuth2AuthenticationToken, got: {}. " +
                    "User may not be logged in via GitHub OAuth.", auth.getClass().getName());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "GITHUB_AUTH_REQUIRED: GitHub authentication required. Please connect your GitHub account.");
        }

        // Get the access token
        String token;
        try {
            token = tokenService.getAccessToken(auth);
            log.info("[GithubFileService] Token retrieved: length={}", token != null ? token.length() : 0);
        } catch (Exception e) {
            log.error("[GithubFileService] FAIL: Could not get access token: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "GITHUB_TOKEN_EXPIRED: GitHub session expired. Please reconnect your GitHub account.");
        }

        if (token == null || token.isBlank()) {
            log.error("[GithubFileService] FAIL: Token is null or blank");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "GITHUB_TOKEN_EXPIRED: GitHub session expired. Please reconnect your GitHub account.");
        }

        // Build the full path
        String fullPath = buildFullPath(subPath, path);
        log.info("[GithubFileService] Full path built: {}", fullPath);

        // Step 5: Call GitHub API
        log.info("[GithubFileService] Calling GitHub API: GET /repos/{}/{}/contents/{}?ref={}",
                owner, repo, fullPath, branch);

        Map<String, Object> response;
        try {
            response = githubClient.getFile(token, owner, repo, fullPath, branch);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("[GithubFileService] FAIL: GitHub API returned 401 Unauthorized");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "GITHUB_TOKEN_EXPIRED: GitHub token expired or revoked. Please reconnect your GitHub account.");
        } catch (HttpClientErrorException.Forbidden e) {
            log.error("[GithubFileService] FAIL: GitHub API returned 403 Forbidden");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "GITHUB_ACCESS_DENIED: Access denied to this repository. Check repository permissions.");
        } catch (HttpClientErrorException.NotFound e) {
            log.error("[GithubFileService] FAIL: GitHub API returned 404 Not Found for {}/{}/{}", owner, repo, fullPath);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "GITHUB_FILE_NOT_FOUND: File not found: " + fullPath);
        } catch (Exception e) {
            log.error("[GithubFileService] FAIL: GitHub API call failed: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GITHUB_API_ERROR: Failed to fetch file from GitHub: " + e.getMessage());
        }

        if (response == null) {
            log.error("[GithubFileService] FAIL: GitHub API returned null response");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GITHUB_API_ERROR: GitHub API returned empty response");
        }

        log.info("[GithubFileService] GitHub API response keys: {}", response.keySet());

        // Step 6: Extract and decode content
        String content = (String) response.get("content");
        String encoding = (String) response.get("encoding");

        log.info("[GithubFileService] Response: encoding={}, content.length={}",
                encoding, content != null ? content.length() : 0);

        if (content == null) {
            log.error("[GithubFileService] FAIL: No 'content' field in GitHub response. Keys present: {}",
                    response.keySet());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GITHUB_API_ERROR: GitHub response missing file content");
        }

        if (!"base64".equals(encoding)) {
            log.error("[GithubFileService] FAIL: Unexpected encoding '{}', expected 'base64'", encoding);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GITHUB_API_ERROR: Unexpected file encoding from GitHub");
        }

        // Step 7: Decode base64 content
        String cleanContent = content.replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleanContent);
        String result = new String(decoded, StandardCharsets.UTF_8);

        log.info("[GithubFileService] SUCCESS: Fetched {}/{}/{} ({} chars, first 80: '{}')",
                owner, repo, fullPath, result.length(),
                result.substring(0, Math.min(80, result.length())).replace("\n", "\\n"));

        return result;
    }

    private String buildFullPath(String subPath, String filePath) {
        if (subPath == null || subPath.isBlank()) {
            return filePath;
        }

        // Normalize subPath
        String cleanSub = subPath.trim()
                .replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");

        // Normalize filePath
        String cleanFile = filePath.trim()
                .replace("\\", "/")
                .replaceAll("^/+", "");

        if (cleanSub.isEmpty()) {
            return cleanFile;
        }

        return cleanSub + "/" + cleanFile;
    }
}
