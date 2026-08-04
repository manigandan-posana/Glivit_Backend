package com.glivt.auth;

import com.glivt.audit.AuditService;
import com.glivt.auth.dto.AuthUser;
import com.glivt.auth.dto.LoginRequest;
import com.glivt.auth.dto.RefreshRequest;
import com.glivt.auth.dto.TokenResponse;
import com.glivt.common.RequestContext;
import com.glivt.common.exception.ForbiddenException;
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
import com.glivt.user.Role;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** Audit action recorded when a session's active tenant changes. */
    public static final String ACTION_SWITCH_TENANT = "SWITCH_TENANT";

    // Constant-time-ish decoy so a missing user costs the same as a wrong password.
    private static final String DECOY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOa8f7f0f5cM2rXwZ1oS9v3o2p0N1kQe6";
    private static final String DEMO_COMPANY_CODE = "DEMO";
    private static final String DEMO_SUPER_ADMIN_USERNAME = "superadmin";

    private final TenantRepository tenantRepository;
    private final TenantAccessService tenantAccessService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final Environment environment;

    @Value("${app.auth.single-session:false}")
    private boolean singleSession;

    @Value("${app.demo-login.enabled:false}")
    private boolean demoLoginEnabled;

    @Value("${app.auth.login-max-attempts:5}")
    private int loginMaxAttempts;

    @Value("${app.auth.login-window-minutes:15}")
    private int loginWindowMinutes;

    public AuthService(TenantRepository tenantRepository, TenantAccessService tenantAccessService,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService, JwtProperties jwtProperties, RateLimiter rateLimiter,
            AuditService auditService, Environment environment) {
        this.tenantRepository = tenantRepository;
        this.tenantAccessService = tenantAccessService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.environment = environment;
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

        boolean isDemoTenant = DEMO_COMPANY_CODE.equalsIgnoreCase(tenant.getCompanyCode());
        boolean isDevDemo = demoLoginEnabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));

        User user = userRepository
                .findByTenantIdAndUsernameOrEmailIgnoreCase(tenant.getId(), request.username().trim())
                .orElse(null);

        boolean matches;
        if (user != null && user.getPasswordHash() != null) {
            matches = passwordEncoder.matches(request.password(), user.getPasswordHash());
        } else {
            passwordEncoder.matches(request.password(), DECOY_HASH);
            matches = false;
        }

        // In development/demo mode for the DEMO company code:
        // If NO user was found with the entered username or email, allow fallback to Demo Super Admin
        if (user == null && isDemoTenant && isDevDemo) {
            user = userRepository
                    .findByTenantIdAndUsernameIgnoreCase(tenant.getId(), DEMO_SUPER_ADMIN_USERNAME)
                    .orElse(null);
            if (user == null) {
                user = new User();
                user.setTenantId(tenant.getId());
                user.setUsername(DEMO_SUPER_ADMIN_USERNAME);
                user.setName("Demo Super Admin");
                user.setEmail("superadmin@example.com");
                user.setRole(Role.SUPER_ADMIN);
                user.setStatus(UserStatus.ACTIVE);
                user.setPasswordHash(passwordEncoder.encode("Admin@12345"));
                user = userRepository.save(user);
            }
            matches = true;
        }

        if (user == null || !matches) {
            auditService.record(tenant.getId(), user != null ? user.getId() : null,
                    request.username(), "LOGIN", "USER", null, "FAILURE", "Invalid credentials");
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid username or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            auditService.record(tenant.getId(), user.getId(),
                    request.username(), "LOGIN", "USER", String.valueOf(user.getId()), "FAILURE", "Account disabled");
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
    public TokenResponse demoLogin() {
        boolean isDevDemo = demoLoginEnabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (!isDevDemo) {
            throw new ForbiddenException("Demo login is disabled");
        }

        String rateKey = "demo-login:" + RequestContext.getClientIp();
        rateLimiter.check(rateKey, loginMaxAttempts, Duration.ofMinutes(loginWindowMinutes));

        Tenant tenant = tenantRepository.findByCompanyCodeIgnoreCase(DEMO_COMPANY_CODE)
                .orElse(null);
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setCompanyCode(DEMO_COMPANY_CODE);
            tenant.setName("Glivt Demo Fleet");
            tenant.setCompanyName("Glivt Demo Logistics Pvt Ltd");
            tenant.setAppName("Glivt Demo");
            tenant.setStatus(TenantStatus.ACTIVE);
            tenant = tenantRepository.save(tenant);
        }

        User user = userRepository
                .findByTenantIdAndUsernameIgnoreCase(tenant.getId(), DEMO_SUPER_ADMIN_USERNAME)
                .orElse(null);
        if (user == null) {
            user = new User();
            user.setTenantId(tenant.getId());
            user.setUsername(DEMO_SUPER_ADMIN_USERNAME);
            user.setName("Demo Super Admin");
            user.setEmail("superadmin@example.com");
            user.setRole(Role.SUPER_ADMIN);
            user.setStatus(UserStatus.ACTIVE);
            user.setPasswordHash(passwordEncoder.encode("Admin@12345"));
            user = userRepository.save(user);
        } else if (user.getRole() != Role.SUPER_ADMIN || user.getStatus() != UserStatus.ACTIVE) {
            user.setRole(Role.SUPER_ADMIN);
            user.setStatus(UserStatus.ACTIVE);
            user = userRepository.save(user);
        }

        if (singleSession) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        rateLimiter.reset(rateKey);
        auditService.record(tenant.getId(), user.getId(), user.getUsername(),
                "DEMO_LOGIN", "USER", String.valueOf(user.getId()), "SUCCESS", null);
        return issueTokens(user, tenant, "demo-login");
    }

    @Transactional
    public TokenResponse adminDemoLogin() {
        boolean isDevDemo = demoLoginEnabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (!isDevDemo) {
            throw new ForbiddenException("Demo login is disabled");
        }

        String rateKey = "demo-login-admin:" + RequestContext.getClientIp();
        rateLimiter.check(rateKey, loginMaxAttempts, Duration.ofMinutes(loginWindowMinutes));

        Tenant tenant = tenantRepository.findByCompanyCodeIgnoreCase(DEMO_COMPANY_CODE).orElse(null);
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setCompanyCode(DEMO_COMPANY_CODE);
            tenant.setName("Glivt Demo Fleet");
            tenant.setCompanyName("Glivt Demo Logistics Pvt Ltd");
            tenant.setAppName("Glivt Demo");
            tenant.setStatus(TenantStatus.ACTIVE);
            tenant = tenantRepository.save(tenant);
        }

        User user = userRepository
                .findByTenantIdAndUsernameIgnoreCase(tenant.getId(), "admin")
                .orElse(null);

        if (user == null) {
            user = new User();
            user.setTenantId(tenant.getId());
            user.setUsername("admin");
            user.setName("Demo Admin");
            user.setEmail("admin@example.com");
            user.setRole(Role.ADMIN);
            user.setStatus(UserStatus.ACTIVE);
            user.setPasswordHash(passwordEncoder.encode("Admin@12345"));
            user = userRepository.save(user);
        } else if (user.getRole() != Role.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
            user.setRole(Role.ADMIN);
            user.setStatus(UserStatus.ACTIVE);
            user = userRepository.save(user);
        }

        if (singleSession) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        rateLimiter.reset(rateKey);
        auditService.record(tenant.getId(), user.getId(), user.getUsername(),
                "DEMO_LOGIN_ADMIN", "USER", String.valueOf(user.getId()), "SUCCESS", null);
        return issueTokens(user, tenant, "demo-login-admin");
    }

    @Transactional
    public TokenResponse driverDemoLogin() {
        boolean isDevDemo = demoLoginEnabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (!isDevDemo) {
            throw new ForbiddenException("Demo login is disabled");
        }

        String rateKey = "demo-login-driver:" + RequestContext.getClientIp();
        rateLimiter.check(rateKey, loginMaxAttempts, Duration.ofMinutes(loginWindowMinutes));

        Tenant tenant = tenantRepository.findByCompanyCodeIgnoreCase(DEMO_COMPANY_CODE).orElse(null);
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setCompanyCode(DEMO_COMPANY_CODE);
            tenant.setName("Glivt Demo Fleet");
            tenant.setCompanyName("Glivt Demo Logistics Pvt Ltd");
            tenant.setAppName("Glivt Demo");
            tenant.setStatus(TenantStatus.ACTIVE);
            tenant = tenantRepository.save(tenant);
        }

        User user = userRepository
                .findByTenantIdAndUsernameIgnoreCase(tenant.getId(), "driver")
                .orElse(null);

        if (user == null) {
            user = new User();
            user.setTenantId(tenant.getId());
            user.setUsername("driver");
            user.setName("Demo Driver");
            user.setEmail("driver@example.com");
            user.setRole(Role.DRIVER);
            user.setStatus(UserStatus.ACTIVE);
            user.setPasswordHash(passwordEncoder.encode("Admin@12345"));
            user = userRepository.save(user);
        } else if (user.getRole() != Role.DRIVER || user.getStatus() != UserStatus.ACTIVE) {
            user.setRole(Role.DRIVER);
            user.setStatus(UserStatus.ACTIVE);
            user = userRepository.save(user);
        }

        if (singleSession) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        rateLimiter.reset(rateKey);
        auditService.record(tenant.getId(), user.getId(), user.getUsername(),
                "DEMO_LOGIN_DRIVER", "USER", String.valueOf(user.getId()), "SUCCESS", null);
        return issueTokens(user, tenant, "demo-login-driver");
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

    @Transactional
    public TokenResponse issueForTenant(User user, Tenant tenant, String deviceInfo) {
        refreshTokenRepository.revokeAllForUser(user.getId());
        return issueTokens(user, tenant, deviceInfo);
    }

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
