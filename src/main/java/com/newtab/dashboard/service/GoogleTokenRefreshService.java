package com.newtab.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newtab.dashboard.entity.User;
import com.newtab.dashboard.exception.UnauthorizedException;
import com.newtab.dashboard.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class GoogleTokenRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleTokenRefreshService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TokenEncryptionService tokenEncryptionService;
    private final UserRepository userRepository;

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    public GoogleTokenRefreshService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            TokenEncryptionService tokenEncryptionService,
            UserRepository userRepository
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tokenEncryptionService = tokenEncryptionService;
        this.userRepository = userRepository;
    }

    public String refreshAccessToken(User user) {
        String refreshToken = tokenEncryptionService.decrypt(user.getGoogleRefreshToken());
        if (refreshToken == null || refreshToken.isBlank()) {
            logger.error("No refresh token available for user: {}", user.getEmail());
            throw new UnauthorizedException(
                    "No refresh token available. Please log out and log back in to grant calendar access."
            );
        }

        if (clientId == null || clientId.isBlank() || clientId.equals("your-google-client-id.apps.googleusercontent.com")) {
            logger.error("Google OAuth client ID not configured");
            throw new UnauthorizedException(
                    "Google OAuth configuration missing. Please contact support."
            );
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        try {
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(
                    "https://oauth2.googleapis.com/token",
                    request,
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            String newAccessToken = json.path("access_token").asText();

            if (newAccessToken != null && !newAccessToken.isBlank()) {
                user.setGoogleAccessToken(tokenEncryptionService.encrypt(newAccessToken));
                userRepository.save(user);
                logger.info("Successfully refreshed access token for user: {}", user.getEmail());
                return newAccessToken;
            }

            throw new UnauthorizedException("Failed to refresh access token: No token in response");
        } catch (Exception ex) {
            logger.error("Token refresh failed for user {}: {}", user.getEmail(), ex.getMessage());
            throw new UnauthorizedException(
                    "Session expired. Please log out and log back in to continue."
            );
        }
    }
}