package com.glivt.auth.dto;

import com.glivt.user.Role;
import java.util.Map;

/** Structured identity + permission object returned after authentication. */
public record AuthUser(
        Long id,
        Long tenantId,
        String username,
        String name,
        String email,
        Role role,
        Map<String, Boolean> permissions) {
}
