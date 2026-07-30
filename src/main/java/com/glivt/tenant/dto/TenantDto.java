package com.glivt.tenant.dto;

import com.glivt.tenant.Tenant;
import java.time.Instant;

/**
 * Tenant row for the Manage Tenants list.
 *
 * <p>{@code current} marks the caller's active tenant so the list can render the
 * Current badge without the client having to infer it, and {@code canDelete}
 * carries the server's deletion verdict so the UI never offers an action the
 * backend will reject.
 */
public record TenantDto(
        Long id,
        String tenantId,
        String name,
        String companyName,
        String adminName,
        String adminEmail,
        String adminPhone,
        String status,
        String appName,
        String logoUrl,
        String primaryColor,
        String secondaryColor,
        Instant createdAt,
        Instant updatedAt,
        boolean current,
        boolean canDelete,
        String deleteBlockedReason) {

    public static TenantDto of(Tenant tenant, boolean current, String deleteBlockedReason) {
        return new TenantDto(
                tenant.getId(),
                tenant.getCompanyCode(),
                tenant.getName(),
                tenant.getCompanyName(),
                tenant.getAdminName(),
                tenant.getAdminEmail(),
                tenant.getAdminPhone(),
                tenant.getStatus().name(),
                tenant.getAppName(),
                tenant.getLogoUrl(),
                tenant.getPrimaryColor(),
                tenant.getSecondaryColor(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                current,
                deleteBlockedReason == null,
                deleteBlockedReason);
    }
}
