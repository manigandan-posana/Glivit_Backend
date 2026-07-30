package com.glivt.security;

import com.glivt.tenant.TenantAccessService;
import com.glivt.tenant.TenantStatus;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantRepository;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.user.UserStatus;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the Bearer access token and reloads the user so role, status and
 * permission changes take effect immediately (no stale cached permissions).
 *
 * <p>It also resolves the ACTIVE TENANT for the request. The tenant comes from the
 * signed {@code tid} claim, but it is re-authorised here against the user's live
 * tenant grants on every single request, so revoking a grant or disabling a tenant
 * locks the caller out immediately rather than at token expiry. A token whose
 * tenant is no longer authorised is treated as unauthenticated - the caller must
 * re-authenticate or switch back, and never silently falls back to a tenant they
 * did not ask for.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    /**
     * Optional client hint naming the tenant the app believes is active. It is
     * never used to grant access; it is only compared with the signed claim so a
     * response can never be delivered to a screen that has already switched away.
     */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantAccessService tenantAccessService;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   TenantRepository tenantRepository,
                                   TenantAccessService tenantAccessService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER.length()).trim();
            authenticate(token, request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            if (!"access".equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
                return;
            }
            Long userId = Long.valueOf(claims.getSubject());
            Optional<User> maybeUser = userRepository.findById(userId);
            if (maybeUser.isEmpty()) {
                return;
            }
            User user = maybeUser.get();
            if (user.getStatus() != UserStatus.ACTIVE) {
                return;
            }
            if (user.getAccountExpiry() != null && user.getAccountExpiry().isBefore(Instant.now())) {
                return;
            }

            Long activeTenantId = resolveActiveTenant(claims, user, request);
            if (activeTenantId == null) {
                return;
            }

            AppUserPrincipal principal = AppUserPrincipal.from(user, activeTenantId);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Audit trail of successful access to a tenant's data. Throttled per
            // user+tenant inside the service, so this does not add a row per request.
            tenantAccessService.recordDataAccess(
                    user.getId(), user.getUsername(), user.getTenantId(), activeTenantId);
        } catch (Exception ex) {
            // Invalid / expired token -> remain unauthenticated; entry point returns 401.
            log.debug("JWT authentication failed: {}", ex.getMessage());
        }
    }

    /**
     * @return the authorised active tenant for this request, or {@code null} to
     *         leave the request unauthenticated.
     */
    private Long resolveActiveTenant(Claims claims, User user, HttpServletRequest request) {
        Long claimed = readLong(claims.get(JwtService.CLAIM_TENANT));
        Long activeTenantId = claimed == null ? user.getTenantId() : claimed;

        // Home tenant needs no grant lookup, but must still be a usable tenant.
        if (!activeTenantId.equals(user.getTenantId())
                && !tenantAccessService.canAccess(user.getId(), user.getTenantId(),
                        user.getRole(), activeTenantId)) {
            log.debug("Rejected token for user {}: tenant {} no longer authorised",
                    user.getId(), activeTenantId);
            return null;
        }

        Tenant tenant = tenantRepository.findById(activeTenantId).orElse(null);
        if (tenant == null || tenant.getStatus() == TenantStatus.DISABLED) {
            log.debug("Rejected token for user {}: tenant {} missing or disabled",
                    user.getId(), activeTenantId);
            return null;
        }

        // A stale client that still believes a previous tenant is active must not
        // receive data for the new one (and vice versa). Mismatch -> unauthenticated,
        // which the app treats as "re-authorise this tenant" rather than a data leak.
        String headerValue = request.getHeader(TENANT_HEADER);
        if (StringUtils.hasText(headerValue)) {
            Long requested = readLong(headerValue.trim());
            if (requested == null || !requested.equals(activeTenantId)) {
                log.debug("Rejected request for user {}: {} header {} does not match active tenant {}",
                        user.getId(), TENANT_HEADER, headerValue, activeTenantId);
                return null;
            }
        }

        return activeTenantId;
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
