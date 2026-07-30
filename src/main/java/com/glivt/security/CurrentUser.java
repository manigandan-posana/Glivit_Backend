package com.glivt.security;

import com.glivt.common.exception.ForbiddenException;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Convenience accessor for the authenticated principal and its tenant scope. */
@Component
public class CurrentUser {

    public AppUserPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new UnauthorizedException("UNAUTHORIZED", "Authentication required");
        }
        return principal;
    }

    /**
     * The tenant the request acts inside. This is the ACTIVE tenant after any
     * tenant switch, already authorised by {@link JwtAuthenticationFilter}, so
     * every caller of this method is automatically scoped to the right tenant.
     */
    public Long tenantId() {
        return require().getTenantId();
    }

    /** The tenant that owns the login, regardless of the active tenant. */
    public Long homeTenantId() {
        return require().getHomeTenantId();
    }

    public Long userId() {
        return require().getUserId();
    }

    /** Enforces a granular permission; Super Admin always passes. */
    public void requirePermission(String key) {
        if (!require().hasPermission(key)) {
            throw new ForbiddenException("Missing permission: " + key);
        }
    }

    /** Enforces an exact role. Used for platform-level operations such as tenant CRUD. */
    public void requireRole(Role role) {
        if (require().getRole() != role) {
            throw new ForbiddenException("This action requires the " + role.name() + " role");
        }
    }
}
