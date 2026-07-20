package com.glivt.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String companyCode,
        @NotBlank String username,
        @NotBlank String password,
        String fcmToken,
        String deviceInfo) {
}
