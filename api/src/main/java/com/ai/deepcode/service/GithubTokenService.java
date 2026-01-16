package com.ai.deepcode.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class GithubTokenService {
    private final OAuth2AuthorizedClientService clientService;

    public GithubTokenService(OAuth2AuthorizedClientService clientService) {
        this.clientService = clientService;
    }

    public String getAccessToken(Authentication authentication) {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;

        OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(
                oauth.getAuthorizedClientRegistrationId(),
                oauth.getName()
        );

        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No GitHub access token found. User not authenticated?");
        }
        return client.getAccessToken().getTokenValue();
    }
}
