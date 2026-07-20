package com.glivt.security;

import com.glivt.user.Role;
import com.glivt.user.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated principal carrying tenant, role and granular permissions. */
public class AppUserPrincipal implements UserDetails {

    public static final String PERMISSION_PREFIX = "PERM_";

    private final Long userId;
    private final Long tenantId;
    private final String username;
    private final Role role;
    private final boolean enabled;
    private final transient Permissions permissions;
    private final List<GrantedAuthority> authorities;

    public AppUserPrincipal(Long userId, Long tenantId, String username, Role role,
                            boolean enabled, Permissions permissions) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.username = username;
        this.role = role;
        this.enabled = enabled;
        this.permissions = permissions;
        this.authorities = buildAuthorities(role, permissions);
    }

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(
                user.getId(),
                user.getTenantId(),
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

    public Long getTenantId() {
        return tenantId;
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
