package com.glivt.user.dto;

import com.glivt.user.Role;
import com.glivt.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record UserUpsertRequest(
        @NotBlank @Size(max = 120) String username,
        @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 160) String name,
        @Email @Size(max = 160) String email,
        @Size(max = 32) String mobile,
        @Size(max = 512) String address,
        @NotNull Role role,
        Long managerId,
        UserStatus status,
        Instant accountExpiry,
        Map<String, Boolean> permissions) {
}
