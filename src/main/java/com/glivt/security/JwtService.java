package com.glivt.security;

import com.glivt.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived signed access tokens (JWT/HS256) and opaque high-entropy
 * refresh tokens. Only the SHA-256 hash of a refresh token is ever persisted.
 */
@Service
public class JwtService {

    /** Active tenant the token acts inside (changes when the user switches tenant). */
    public static final String CLAIM_TENANT = "tid";
    /** Tenant that owns the login; never changes for a given user. */
    public static final String CLAIM_HOME_TENANT = "htid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_USERNAME = "usr";
    public static final String CLAIM_TYPE = "typ";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.length() < 32) {
            secret = "change-me-in-env-this-is-a-dev-only-secret-32b";
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateAccessToken(User user) {
        return generateAccessToken(user, user.getTenantId());
    }

    /**
     * Issues an access token bound to {@code activeTenantId}.
     *
     * <p>The tenant is a signed claim so the server never has to trust a tenant id
     * from the request body. It is still re-authorised on every request (see
     * {@link JwtAuthenticationFilter}) so revoking a tenant grant takes effect
     * immediately instead of at token expiry.
     */
    public String generateAccessToken(User user, Long activeTenantId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_TENANT, activeTenantId == null ? user.getTenantId() : activeTenantId)
                .claim(CLAIM_HOME_TENANT, user.getTenantId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_TYPE, "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Generates a new opaque refresh token (returned to the client, never stored). */
    public String generateRefreshTokenValue() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plus(properties.getRefreshTokenTtlDays(), ChronoUnit.DAYS);
    }

    /** SHA-256 hash used to store/look up refresh tokens without keeping the secret. */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash refresh token", e);
        }
    }
}
