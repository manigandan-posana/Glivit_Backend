package com.glivt.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Explicit tenant access grant for a user.
 *
 * <p>This is the authoritative answer to "may this user act inside this tenant?".
 * Every login has exactly one {@code homeTenant} row matching {@code users.tenant_id};
 * additional rows grant multi-tenant access. Tenant switching validates the
 * requested tenant against these rows and never against a client-supplied id.
 */
@Entity
@Table(name = "tenant_users", uniqueConstraints = @UniqueConstraint(
        name = "uk_tenant_users_user_tenant", columnNames = {"user_id", "tenant_id"}))
@Getter
@Setter
@NoArgsConstructor
public class TenantUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** True for the user's own tenant ({@code users.tenant_id}); it can never be revoked. */
    @Column(name = "home_tenant", nullable = false)
    private boolean homeTenant = false;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public static TenantUser grant(Long tenantId, Long userId, boolean home, Long grantedBy) {
        TenantUser mapping = new TenantUser();
        mapping.setTenantId(tenantId);
        mapping.setUserId(userId);
        mapping.setHomeTenant(home);
        mapping.setGrantedBy(grantedBy);
        return mapping;
    }
}
