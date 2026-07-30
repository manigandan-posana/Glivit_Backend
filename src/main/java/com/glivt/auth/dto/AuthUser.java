package com.glivt.auth.dto;

import com.glivt.user.Role;
import java.util.Map;

/**
 * Structured identity + permission object returned after authentication.
 *
 * <p>{@code tenantId} is the ACTIVE tenant this session acts inside, so the client
 * can key every cache and storage entry by it. {@code homeTenantId} is the tenant
 * that owns the login and never changes; the two differ exactly while the user has
 * switched into another tenant.
 */
public record AuthUser(
        Long id,
        Long tenantId,
        Long homeTenantId,
        String tenantCode,
        String tenantName,
        String companyName,
        String username,
        String name,
        String email,
        Role role,
        Map<String, Boolean> permissions) {
}
