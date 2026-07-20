package com.glivt.security;

import com.glivt.common.exception.ForbiddenException;
import com.glivt.common.exception.UnauthorizedException;
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

    public Long tenantId() {
        return require().getTenantId();
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
}
