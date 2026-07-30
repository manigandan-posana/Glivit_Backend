package com.glivt.security;

import com.glivt.user.Role;
import com.glivt.user.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal carrying tenant, role and granular permissions.
 *
 * <p>Two tenant ids are carried, and the distinction matters:
 * <ul>
 *   <li>{@code homeTenantId} is {@code users.tenant_id} — the tenant that owns the
 *       login. It never changes and is the basis for access checks.</li>
 *   <li>{@code activeTenantId} is the tenant the request is currently acting
 *       inside, taken from the signed access token and re-authorised on every
 *       request. It is what {@link #getTenantId()} returns, so every existing
 *       tenant-scoped query follows a tenant switch with no further changes.</li>
 * </ul>
 */
public class AppUserPrincipal implements UserDetails {

    public static final String PERMISSION_PREFIX = "PERM_";

    private final Long userId;
    private final Long homeTenantId;
    private final Long activeTenantId;
    private final String username;
    private final Role role;
    private final boolean enabled;
    private final transient Permissions permissions;
    private final List<GrantedAuthority> authorities;

    public AppUserPrincipal(Long userId, Long homeTenantId, Long activeTenantId, String username,
                            Role role, boolean enabled, Permissions permissions) {
        this.userId = userId;
        this.homeTenantId = homeTenantId;
        this.activeTenantId = activeTenantId == null ? homeTenantId : activeTenantId;
        this.username = username;
        this.role = role;
        this.enabled = enabled;
        this.permissions = permissions;
        this.authorities = buildAuthorities(role, permissions);
    }

    public static AppUserPrincipal from(User user) {
        return from(user, user.getTenantId());
    }

    /**
     * Builds the principal for a request acting inside {@code activeTenantId}. The
     * caller MUST have authorised that tenant first (see {@code TenantAccessService});
     * this constructor performs no authorisation of its own.
     */
    public static AppUserPrincipal from(User user, Long activeTenantId) {
        return new AppUserPrincipal(
                user.getId(),
                user.getTenantId(),
                activeTenantId,
                user.getUsername(),
                user.getRole(),
                user.getStatus() == com.glivt.user.UserStatus.ACTIVE,
                Permissions.forUser(user.getRole(), user.getPermissions()));
    }

    private static List<GrantedAuthority> buildAuthorities(Role role, Permissions permissions) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        permissions.asMap().forEach((key, granted) -> {
            if (Boolean.TRUE.equals(granted)) {
                list.add(new SimpleGrantedAuthority(PERMISSION_PREFIX + key));
            }
        });
        return list;
    }

    public Long getUserId() {
        return userId;
    }

    /** The tenant this request acts inside. Every tenant-scoped query uses this. */
    public Long getTenantId() {
        return activeTenantId;
    }

    /** The tenant that owns the login; unaffected by tenant switching. */
    public Long getHomeTenantId() {
        return homeTenantId;
    }

    /** True when the request is acting inside a tenant other than the login's own. */
    public boolean isSwitchedTenant() {
        return homeTenantId != null && !homeTenantId.equals(activeTenantId);
    }

    public Role getRole() {
        return role;
    }

    public Permissions getPermissions() {
        return permissions;
    }

    public boolean hasPermission(String key) {
        return permissions.has(key);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null; // Authentication is performed against the DB in AuthService.
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
