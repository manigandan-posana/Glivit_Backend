package com.glivt.tenant;

import com.glivt.audit.AuditService;
import com.glivt.common.exception.ForbiddenException;
import com.glivt.security.AppUserPrincipal;
import com.glivt.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side authority on which tenants a user may act inside.
 *
 * <p>
 * Every tenant id that reaches a repository query must have passed through
 * here first. The rules:
 *
 * <ul>
 * <li>A user may always act inside their home tenant
 * ({@code users.tenant_id}).</li>
 * <li>Any additional tenant requires an explicit {@code tenant_users}
 * grant.</li>
 * <li>A platform SUPER_ADMIN (a Super Admin holding {@code manage_tenants})
 * may act inside any tenant; that is what makes the tenant switcher useful
 * for operators, and it is still a server-side decision.</li>
 * <li>A DISABLED tenant can never become the active tenant, whatever the
 * caller's role.</li>
 * </ul>
 *
 * <p>
 * Every rejection is audited as {@code TENANT_ACCESS_DENIED} so cross-tenant
 * probing is visible in the audit trail rather than silently 403-ing.
 */
@Service
public class TenantAccessService {

    public static final String ACTION_ACCESS_DENIED = "TENANT_ACCESS_DENIED";
    public static final String ACTION_DATA_ACCESS = "TENANT_DATA_ACCESS";

    /**
     * How long one recorded data-access entry covers a (user, tenant) pair.
     *
     * A row per HTTP request would make the audit table grow with traffic and add a
     * write to every read, which is not a trade a fleet-tracking backend can make.
     * One entry per user, per tenant, per window still answers the question the
     * audit
     * trail exists to answer - who was reading which tenant's data, and when -
     * without
     * turning every dashboard poll into an insert.
     */
    private static final Duration DATA_ACCESS_WINDOW = Duration.ofMinutes(15);

    /** Last recorded data-access time per user+tenant, for the throttle above. */
    private final Map<String, Instant> lastDataAccess = new ConcurrentHashMap<>();

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final AuditService auditService;

    public TenantAccessService(TenantRepository tenantRepository,
            TenantUserRepository tenantUserRepository,
            AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.auditService = auditService;
    }

    /**
     * True when the role may administer tenants (create / edit / delete / switch
     * anywhere).
     */
    public boolean isPlatformAdmin(Role role) {
        return role == Role.SUPER_ADMIN;
    }

    /**
     * Tenant ids the user may act inside, home tenant first. For a platform admin
     * this is every tenant on the platform.
     */
    @Transactional(readOnly = true)
    public Set<Long> accessibleTenantIds(Long userId, Long homeTenantId, Role role) {
        if (isPlatformAdmin(role)) {
            Set<Long> all = new LinkedHashSet<>();
            if (homeTenantId != null) {
                all.add(homeTenantId);
            }
            tenantRepository.findAll().forEach(t -> all.add(t.getId()));
            return all;
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (homeTenantId != null) {
            ids.add(homeTenantId);
        }
        ids.addAll(tenantUserRepository.tenantIdsForUser(userId));
        return ids;
    }

    /** Tenants the user may switch to, in switcher display order. */
    @Transactional(readOnly = true)
    public List<Tenant> accessibleTenants(Long userId, Long homeTenantId, Role role) {
        if (isPlatformAdmin(role)) {
            return tenantRepository.findAll(
                    org.springframework.data.domain.Sort.by("name")).stream().toList();
        }
        Set<Long> ids = accessibleTenantIds(userId, homeTenantId, role);
        if (ids.isEmpty()) {
            return List.of();
        }
        return tenantRepository.findByIdIn(ids).stream()
                .sorted(java.util.Comparator.comparing(Tenant::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** True when the user is authorised for the tenant, ignoring tenant status. */
    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long homeTenantId, Role role, Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        if (tenantId.equals(homeTenantId)) {
            return true;
        }
        if (isPlatformAdmin(role)) {
            return tenantRepository.existsById(tenantId);
        }
        return tenantUserRepository.existsByUserIdAndTenantId(userId, tenantId);
    }

    /**
     * Authorises the tenant for the principal and returns it, or throws.
     *
     * @throws ForbiddenException when the caller is not authorised for the tenant,
     *                            or the tenant is not usable (missing / disabled).
     */
    @Transactional(readOnly = true)
    public Tenant requireAccess(AppUserPrincipal principal, Long tenantId) {
        if (!canAccess(principal.getUserId(), principal.getHomeTenantId(),
                principal.getRole(), tenantId)) {
            denied(principal, tenantId, "Tenant not authorised for this user");
            // Deliberately the same message and status for "no such tenant" and
            // "not yours": the response must not reveal that a tenant exists.
            throw new ForbiddenException("You do not have access to this tenant");
        }
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            denied(principal, tenantId, "Tenant does not exist");
            throw new ForbiddenException("You do not have access to this tenant");
        }
        if (tenant.getStatus() == TenantStatus.DISABLED) {
            denied(principal, tenantId, "Tenant is disabled");
            throw new ForbiddenException("This tenant is disabled");
        }
        return tenant;
    }

    /** Records a rejected cross-tenant access attempt. */
    public void denied(AppUserPrincipal principal, Long tenantId, String reason) {
        auditService.record(principal.getTenantId(), principal.getUserId(),
                principal.getUsername(), ACTION_ACCESS_DENIED, "TENANT",
                tenantId == null ? null : String.valueOf(tenantId), "FAILURE", reason);
    }

    /**
     * Records that a user successfully accessed a tenant's data, at most once per
     * {@link #DATA_ACCESS_WINDOW} per user+tenant.
     *
     * The entry that matters for an audit is the first read of a tenant's data in a
     * session, especially a tenant that is not the user's own; the thousandth
     * live-map
     * poll in the same minute adds nothing. Failures here are swallowed by
     * {@link AuditService}, so auditing can never break a request.
     */
    public void recordDataAccess(Long userId, String username, Long homeTenantId, Long tenantId) {
        if (userId == null || tenantId == null) {
            return;
        }
        String key = userId + ":" + tenantId;
        Instant now = Instant.now();
        Instant previous = lastDataAccess.get(key);
        if (previous != null && previous.plus(DATA_ACCESS_WINDOW).isAfter(now)) {
            return;
        }
        lastDataAccess.put(key, now);

        boolean switched = homeTenantId != null && !homeTenantId.equals(tenantId);
        auditService.record(tenantId, userId, username, ACTION_DATA_ACCESS, "TENANT",
                String.valueOf(tenantId), "SUCCESS",
                switched ? "Data access in a switched (non-home) tenant" : "Data access in home tenant");
    }

    /**
     * Drops throttle entries for a user, so a new session audits its first read
     * again.
     */
    public void resetDataAccessThrottle(Long userId) {
        if (userId == null) {
            return;
        }
        lastDataAccess.keySet().removeIf(key -> key.startsWith(userId + ":"));
    }
}
