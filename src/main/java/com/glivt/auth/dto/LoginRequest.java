package com.glivt.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(min = 2, max = 50) String companyCode,
        @NotBlank @Size(min = 2, max = 50) String username,
        @NotBlank @Size(min = 4, max = 100) String password,
        String fcmToken,
        String deviceInfo) {
}
