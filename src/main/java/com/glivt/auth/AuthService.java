package com.glivt.auth;

import com.glivt.audit.AuditService;
import com.glivt.auth.dto.AuthUser;
import com.glivt.auth.dto.LoginRequest;
import com.glivt.auth.dto.RefreshRequest;
import com.glivt.auth.dto.TokenResponse;
import com.glivt.common.RequestContext;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.security.JwtProperties;
import com.glivt.security.JwtService;
import com.glivt.security.Permissions;
import com.glivt.security.RefreshToken;
import com.glivt.security.RefreshTokenRepository;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantAccessService;
import com.glivt.tenant.TenantRepository;
import com.glivt.tenant.TenantStatus;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** Audit action recorded when a session's active tenant changes. */
    public static final String ACTION_SWITCH_TENANT = "SWITCH_TENANT";

    // Constant-time-ish decoy so a missing user costs the same as a wrong password.
    private static final String DECOY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOa8f7f0f5cM2rXwZ1oS9v3o2p0N1kQe6";

    private final TenantRepository tenantRepository;
    private final TenantAccessService tenantAccessService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    @Value("${app.auth.single-session:false}")
    private boolean singleSession;

    @Value("${app.auth.login-max-attempts:5}")
    private int loginMaxAttempts;

    @Value("${app.auth.login-window-minutes:15}")
    private int loginWindowMinutes;

    public AuthService(TenantRepository tenantRepository, TenantAccessService tenantAccessService,
                       UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, JwtProperties jwtProperties, RateLimiter rateLimiter,
                       AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.tenantAccessService = tenantAccessService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        String rateKey = "login:" + request.companyCode() + ":" + request.username()
                + ":" + RequestContext.getClientIp();
        rateLimiter.check(rateKey, loginMaxAttempts, Duration.ofMinutes(loginWindowMinutes));

        Tenant tenant = tenantRepository.findByCompanyCodeIgnoreCase(request.companyCode())
                .orElseThrow(() -> new UnauthorizedException("INVALID_COMPANY_CODE",
                        "Invalid company code"));
        if (tenant.getStatus() == TenantStatus.DISABLED) {
            throw new UnauthorizedException("TENANT_DISABLED", "This account has been disabled");
        }
        if (tenant.getStatus() == TenantStatus.MAINTENANCE) {
            throw new UnauthorizedException("MAINTENANCE", "Service is under maintenance");
        }

        User user = userRepository
                .findByTenantIdAndUsernameIgnoreCase(tenant.getId(), request.username())
                .orElse(null);

        boolean matches = passwordEncoder.matches(
                request.password(), user != null ? user.getPasswordHash() : DECOY_HASH);
        if (user == null || !matches) {
            auditService.record(tenant.getId(), user != null ? user.getId() : null,
                    request.username(), "LOGIN", "USER", null, "FAILURE", "Invalid credentials");
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid username or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("ACCOUNT_DISABLED", "Your account is disabled");
        }
        if (user.getAccountExpiry() != null && user.getAccountExpiry().isBefore(Instant.now())) {
            throw new UnauthorizedException("SUBSCRIPTION_EXPIRED", "Your subscription has expired");
        }

        if (request.fcmToken() != null && !request.fcmToken().isBlank()) {
            user.setFcmToken(request.fcmToken());
            userRepository.save(user);
        }

        if (singleSession) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        rateLimiter.reset(rateKey);
        auditService.record(tenant.getId(), user.getId(), user.getUsername(),
                "LOGIN", "USER", String.valueOf(user.getId()), "SUCCESS", null);
        // Logging in always starts inside the tenant whose company code was used.
        return issueTokens(user, tenant, request.deviceInfo());
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        String hash = jwtService.hashRefreshToken(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH",
                        "Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("INVALID_REFRESH", "Refresh token expired");
        }
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH", "User not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("ACCOUNT_DISABLED", "Your account is disabled");
        }

        // Rotation preserves the ACTIVE tenant recorded on the presented token. If
        // that tenant is no longer authorised (grant revoked, tenant disabled) the
        // refresh is refused rather than quietly falling back to the home tenant,
        // which would move the user's data view without them asking.
        Tenant activeTenant = resolveRefreshTenant(user, stored);

        TokenResponse response = issueTokens(user, activeTenant, stored.getDeviceInfo());
        stored.setRevoked(true);
        stored.setReplacedBy(jwtService.hashRefreshToken(response.refreshToken()));
        refreshTokenRepository.save(stored);
        return response;
    }

    private Tenant resolveRefreshTenant(User user, RefreshToken stored) {
        Long activeTenantId = stored.getActiveTenantId() == null
                ? user.getTenantId()
                : stored.getActiveTenantId();
        if (!activeTenantId.equals(user.getTenantId())
                && !tenantAccessService.canAccess(user.getId(), user.getTenantId(),
                        user.getRole(), activeTenantId)) {
            throw new UnauthorizedException("TENANT_NOT_AUTHORISED",
                    "You no longer have access to this tenant");
        }
        Tenant tenant = tenantRepository.findById(activeTenantId)
                .orElseThrow(() -> new UnauthorizedException("TENANT_NOT_AUTHORISED",
                        "This tenant is no longer available"));
        if (tenant.getStatus() == TenantStatus.DISABLED) {
            throw new UnauthorizedException("TENANT_DISABLED", "This tenant has been disabled");
        }
        return tenant;
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId);
        auditService.record(null, userId, null, "LOGOUT", "USER",
                String.valueOf(userId), "SUCCESS", null);
    }

    /**
     * Issues a session bound to a different active tenant.
     *
     * <p>The tenant must already have been authorised by the caller
     * ({@code TenantAccessService#requireAccess}); this method only mints the
     * session. Existing refresh tokens are revoked so the previous tenant's session
     * cannot be refreshed back into life alongside the new one - one device, one
     * active tenant. The audit entry is written by the caller, which knows why the
     * session was re-issued.
     */
    @Transactional
    public TokenResponse issueForTenant(User user, Tenant tenant, String deviceInfo) {
        refreshTokenRepository.revokeAllForUser(user.getId());
        return issueTokens(user, tenant, deviceInfo);
    }

    private TokenResponse issueTokens(User user, String deviceInfo) {
        return issueTokens(user, null, deviceInfo);
    }

    /** @param activeTenant the tenant the new session acts inside; null = the user's own. */
    private TokenResponse issueTokens(User user, Tenant activeTenant, String deviceInfo) {
        Tenant tenant = activeTenant != null
                ? activeTenant
                : tenantRepository.findById(user.getTenantId()).orElse(null);
        Long activeTenantId = tenant != null ? tenant.getId() : user.getTenantId();

        String accessToken = jwtService.generateAccessToken(user, activeTenantId);
        String refreshValue = jwtService.generateRefreshTokenValue();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(jwtService.hashRefreshToken(refreshValue));
        refreshToken.setExpiresAt(jwtService.refreshTokenExpiry());
        refreshToken.setDeviceInfo(deviceInfo);
        refreshToken.setActiveTenantId(activeTenantId);
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(accessToken, refreshValue, "Bearer",
                jwtProperties.getAccessTokenTtlMinutes() * 60, toAuthUser(user, tenant));
    }

    private AuthUser toAuthUser(User user, Tenant activeTenant) {
        Permissions permissions = Permissions.forUser(user.getRole(), user.getPermissions());
        Long activeTenantId = activeTenant != null ? activeTenant.getId() : user.getTenantId();
        return new AuthUser(
                user.getId(),
                activeTenantId,
                user.getTenantId(),
                activeTenant != null ? activeTenant.getCompanyCode() : null,
                activeTenant != null ? activeTenant.getName() : null,
                activeTenant != null ? activeTenant.getCompanyName() : null,
                user.getUsername(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                permissions.asMap());
    }
}
