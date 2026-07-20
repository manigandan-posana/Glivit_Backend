package com.glivt.security;

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
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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
            AppUserPrincipal principal = AppUserPrincipal.from(user);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception ex) {
            // Invalid / expired token -> remain unauthenticated; entry point returns 401.
            log.debug("JWT authentication failed: {}", ex.getMessage());
        }
    }
}
