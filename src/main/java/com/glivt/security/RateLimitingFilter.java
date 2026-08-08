package com.glivt.security;

import com.glivt.common.ApiError;
import com.glivt.common.ApiResponse;
import com.glivt.common.exception.TooManyRequestsException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Filter to enforce Bucket4j rate limits across API endpoints.
 * Runs in the Spring Security filter chain after JWT authentication.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${app.rate-limiting.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limiting.login.limit:5}")
    private int loginLimit;

    @Value("${app.rate-limiting.ai-chat.limit:20}")
    private int aiChatLimit;

    @Value("${app.rate-limiting.vehicle-location.limit:60}")
    private int vehicleLocationLimit;

    @Value("${app.rate-limiting.general.limit:100}")
    private int generalLimit;

    public RateLimitingFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // Apply rate limits only to /api/** endpoints when enabled
        if (!enabled || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (isLoginPath(path)) {
                String key = "login:" + getClientIp(request);
                checkLimit(key, loginLimit, Duration.ofMinutes(1));
            } else if (isAiPath(path)) {
                String key = getUserOrIpKey("ai:", request);
                checkLimit(key, aiChatLimit, Duration.ofMinutes(1));
            } else if (isVehicleLocationPath(path)) {
                String key = getUserOrIpKey("vehloc:", request);
                checkLimit(key, vehicleLocationLimit, Duration.ofMinutes(1));
            } else {
                String key = "general:" + getClientIp(request);
                checkLimit(key, generalLimit, Duration.ofMinutes(1));
            }
            filterChain.doFilter(request, response);
        } catch (TooManyRequestsException ex) {
            log.warn("Rate limit exceeded for path [{}], client IP [{}]", path, getClientIp(request));
            writeErrorResponse(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage());
        }
    }

    private boolean isLoginPath(String path) {
        return path.equals("/api/auth/login") || path.equals("/api/auth/demo-login") || path.startsWith("/api/auth/demo/");
    }

    private boolean isAiPath(String path) {
        return path.startsWith("/api/ai");
    }

    private boolean isVehicleLocationPath(String path) {
        return path.startsWith("/api/devices") || path.startsWith("/api/positions");
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String getUserOrIpKey(String prefix, HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return prefix + "user:" + principal.getUserId();
        }
        return prefix + "ip:" + getClientIp(request);
    }

    private void checkLimit(String key, int limit, Duration period) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(limit, Refill.greedy(limit, period)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException("Too many requests. Try again later.");
        }
    }

    public void reset() {
        buckets.clear();
        log.info("Rate limiting buckets reset.");
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.fail(ApiError.of(code, message));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
