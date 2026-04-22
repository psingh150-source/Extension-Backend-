package com.newtab.dashboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {

    @NotBlank(message = "Google access token is required")
    private String accessToken;

    private String refreshToken;
}
