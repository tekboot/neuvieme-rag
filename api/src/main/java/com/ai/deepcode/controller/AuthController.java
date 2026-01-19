package com.ai.deepcode.controller;

import com.ai.deepcode.service.GithubTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final GithubTokenService githubTokenService;

    public AuthController(GithubTokenService githubTokenService) {
        this.githubTokenService = githubTokenService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        Map<String, Object> result = new HashMap<>();

        if (auth == null) {
            result.put("authenticated", false);
            result.put("githubAuthenticated", false);
            return result;
        }

        result.put("authenticated", true);
        result.put("name", auth.getName());

        // Check if GitHub OAuth token is available and valid
        boolean githubAuthenticated = false;
        if (auth instanceof OAuth2AuthenticationToken) {
            try {
                String token = githubTokenService.getAccessToken(auth);
                githubAuthenticated = (token != null && !token.isBlank());
            } catch (Exception e) {
                // Token retrieval failed - GitHub not authenticated
                githubAuthenticated = false;
            }
        }
        result.put("githubAuthenticated", githubAuthenticated);

        return result;
    }
}
