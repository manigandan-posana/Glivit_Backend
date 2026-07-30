package com.glivt.tenant.dto;

import com.glivt.auth.dto.TokenResponse;

/**
 * Result of a successful tenant switch.
 *
 * <p>Carries both halves the client needs to finish the switch atomically: the new
 * session (whose access token is bound to the new tenant) and the new tenant's
 * white-label configuration, so the drawer, theme and company name update in the
 * same commit rather than after a second round trip.
 */
public record TenantSwitchResponse(
        TokenResponse session,
        TenantConfigResponse tenant,
        TenantDto activeTenant) {
}
