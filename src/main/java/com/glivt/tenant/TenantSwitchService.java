package com.glivt.tenant;

import com.glivt.audit.AuditService;
import com.glivt.auth.AuthService;
import com.glivt.auth.dto.TokenResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.security.AppUserPrincipal;
import com.glivt.tenant.dto.TenantDto;
import com.glivt.tenant.dto.TenantSwitchResponse;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Switches the active tenant for the caller's session.
 *
 * <p>The whole switch is one transaction: authorise, revoke the old session, mint a
 * session bound to the new tenant. If any step fails nothing is committed, so the
 * caller's previous tenant session stays valid and the client can safely restore it
 * and retry — there is no half-switched state on the server.
 *
 * <p>Switching to the tenant that is already active is refused rather than silently
 * re-issuing a session; the client uses that to keep the confirmation dialog honest.
 */
@Service
public class TenantSwitchService {

    private final TenantAccessService tenantAccessService;
    private final TenantService tenantService;
    private final TenantAdminService tenantAdminService;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;

    public TenantSwitchService(TenantAccessService tenantAccessService,
                               TenantService tenantService,
                               TenantAdminService tenantAdminService,
                               UserRepository userRepository,
                               AuthService authService,
                               AuditService auditService) {
        this.tenantAccessService = tenantAccessService;
        this.tenantService = tenantService;
        this.tenantAdminService = tenantAdminService;
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public TenantSwitchResponse switchTo(AppUserPrincipal principal, Long tenantId, String deviceInfo) {
        if (tenantId == null) {
            throw new BadRequestException("Target tenant is required");
        }
        if (tenantId.equals(principal.getTenantId())) {
            throw new BadRequestException("This tenant is already active");
        }

        // Authorisation first: throws 403 (and audits the attempt) for a tenant the
        // caller may not use, and for a DISABLED tenant whatever their role.
        Tenant target = tenantAccessService.requireAccess(principal, tenantId);

        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new UnauthorizedException("UNAUTHORIZED", "Authentication required"));

        TokenResponse session = authService.issueForTenant(user, target, deviceInfo);

        // The first data read inside the new tenant should appear in the audit trail
        // rather than be swallowed by a throttle window opened under the old tenant.
        tenantAccessService.resetDataAccessThrottle(user.getId());

        // Rebuild the principal against the new tenant so the returned row is marked
        // current and its delete verdict reflects the post-switch state.
        AppUserPrincipal switched = AppUserPrincipal.from(user, target.getId());
        TenantDto activeTenant = tenantAdminService.get(switched, target.getId());

        auditService.record(target.getId(), user.getId(), user.getUsername(),
                AuthService.ACTION_SWITCH_TENANT, "TENANT", String.valueOf(target.getId()),
                "SUCCESS", "Switched into " + target.getCompanyCode());

        return new TenantSwitchResponse(session, tenantService.toConfig(target), activeTenant);
    }
}
