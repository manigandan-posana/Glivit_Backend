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

    // Constant-time-ish decoy so a missing user costs the same as a wrong password.
    private static final String DECOY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOa8f7f0f5cM2rXwZ1oS9v3o2p0N1kQe6";

    private final TenantRepository tenantRepository;
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

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, JwtProperties jwtProperties, RateLimiter rateLimiter,
                       AuditService auditService) {
        this.tenantRepository = tenantRepository;
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
        return issueTokens(user, request.deviceInfo());
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

        // Rotation: revoke the presented token and issue a fresh pair.
        TokenResponse response = issueTokens(user, stored.getDeviceInfo());
        stored.setRevoked(true);
        stored.setReplacedBy(jwtService.hashRefreshToken(response.refreshToken()));
        refreshTokenRepository.save(stored);
        return response;
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId);
        auditService.record(null, userId, null, "LOGOUT", "USER",
                String.valueOf(userId), "SUCCESS", null);
    }

    private TokenResponse issueTokens(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshValue = jwtService.generateRefreshTokenValue();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(jwtService.hashRefreshToken(refreshValue));
        refreshToken.setExpiresAt(jwtService.refreshTokenExpiry());
        refreshToken.setDeviceInfo(deviceInfo);
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(accessToken, refreshValue, "Bearer",
                jwtProperties.getAccessTokenTtlMinutes() * 60, toAuthUser(user));
    }

    private AuthUser toAuthUser(User user) {
        Permissions permissions = Permissions.forUser(user.getRole(), user.getPermissions());
        return new AuthUser(user.getId(), user.getTenantId(), user.getUsername(),
                user.getName(), user.getEmail(), user.getRole(), permissions.asMap());
    }
}
