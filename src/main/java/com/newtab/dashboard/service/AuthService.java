package com.newtab.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newtab.dashboard.dto.request.GoogleLoginRequest;
import com.newtab.dashboard.dto.response.AuthResponse;
import com.newtab.dashboard.dto.response.UserResponse;
import com.newtab.dashboard.entity.User;
import com.newtab.dashboard.enums.Role;
import com.newtab.dashboard.exception.UnauthorizedException;
import com.newtab.dashboard.repository.UserRepository;
import com.newtab.dashboard.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Required scopes for calendar operations and email notifications
    private static final String[] REQUIRED_SCOPES = {
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.readonly",
            "https://www.googleapis.com/auth/calendar.events.send" // Required for email notifications
    };

    public AuthService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            TokenEncryptionService tokenEncryptionService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenEncryptionService = tokenEncryptionService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        String accessToken = request.getAccessToken();
        String refreshToken = request.getRefreshToken();

        if (accessToken == null || accessToken.isBlank()) {
            throw new UnauthorizedException("Missing Google access token");
        }

        // Validate token and check required scopes
        validateTokenAndScopes(accessToken);

        JsonNode userInfo = fetchUserInfo(accessToken);
        String email = userInfo.path("email").asText(null);
        String name = userInfo.path("name").asText("User");
        String picture = userInfo.path("picture").asText(null);

        if (email == null) {
            throw new UnauthorizedException("Failed to fetch Google user profile");
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> User.builder()
                .email(email)
                .role(Role.USER)
                .build());

        user.setName(name);
        user.setPictureUrl(picture);
        user.setGoogleAccessToken(tokenEncryptionService.encrypt(accessToken));
        if (refreshToken != null && !refreshToken.isBlank()) {
            user.setGoogleRefreshToken(tokenEncryptionService.encrypt(refreshToken));
        }

        User savedUser = userRepository.save(user);

        String jwt = jwtTokenProvider.generateToken(savedUser);

        logger.info("User logged in successfully: {}", email);

        return new AuthResponse(jwt, "Bearer", toUserResponse(savedUser));
    }

    private JsonNode fetchUserInfo(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo",
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            return objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            logger.error("Failed to fetch user info from Google", ex);
            throw new UnauthorizedException("Google userinfo request failed");
        }
    }

    private void validateTokenAndScopes(String accessToken) {
        try {
            // First, validate the token
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=" + accessToken,
                    String.class
            );
            JsonNode tokenInfo = objectMapper.readTree(response.getBody());
            String scope = tokenInfo.path("scope").asText("");

            // Check if token has expired
            Long expiry = tokenInfo.path("exp").asLong();
            if (expiry != null && expiry * 1000 < System.currentTimeMillis()) {
                throw new UnauthorizedException("Google access token has expired");
            }

            // Check for required calendar scopes including email notifications
            boolean hasCalendarScope = false;
            boolean hasEmailNotificationScope = false;

            for (String requiredScope : REQUIRED_SCOPES) {
                if (scope.contains(requiredScope)) {
                    if (requiredScope.contains("calendar.events.send")) {
                        hasEmailNotificationScope = true;
                    } else {
                        hasCalendarScope = true;
                    }
                }
            }

            if (!hasCalendarScope) {
                logger.error("Missing required calendar scopes. Current scopes: {}", scope);
                throw new UnauthorizedException(
                        "Calendar access not granted. Please log out and log back in, and make sure to grant calendar permissions."
                );
            }

            if (!hasEmailNotificationScope) {
                logger.warn("Missing email notification scope. Email invites may not be sent. Current scopes: {}", scope);
                // Don't throw exception, just warn - the meeting can still be created without email notifications
            }

            logger.info("Token validated successfully with calendar scopes. Email notifications scope present: {}", hasEmailNotificationScope);

        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.error("Token validation error", ex);
            throw new UnauthorizedException("Invalid or expired Google access token");
        }
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .pictureUrl(user.getPictureUrl())
                .build();
    }
}