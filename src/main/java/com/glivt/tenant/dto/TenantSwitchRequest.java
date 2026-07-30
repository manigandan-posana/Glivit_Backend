package com.glivt.tenant.dto;

import jakarta.validation.constraints.Size;

/**
 * Tenant switch payload.
 *
 * <p>The target tenant is the path variable, not a body field, and it is authorised
 * server-side against the caller's tenant grants before any session is issued.
 * {@code deviceInfo} is carried through to the new refresh token so the session
 * list stays meaningful across a switch.
 */
public record TenantSwitchRequest(
        @Size(max = 256, message = "Device info must be 256 characters or fewer")
        String deviceInfo) {
}
