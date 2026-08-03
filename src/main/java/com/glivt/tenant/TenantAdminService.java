package com.glivt.tenant;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.DuplicateResourceException;
import com.glivt.common.exception.ForbiddenException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.security.AppUserPrincipal;
import com.glivt.tenant.dto.TenantCreateRequest;
import com.glivt.tenant.dto.TenantDto;
import com.glivt.tenant.dto.TenantUpdateRequest;
import com.glivt.user.Role;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.user.UserStatus;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant administration: list, create, update and delete.
 *
 * <p>Only a SUPER_ADMIN may mutate tenants. Everyone else can only ever see the
 * tenants they are authorised for, and the list they receive is built from
 * {@code tenant_users} on the server - a client cannot widen it.
 *
 * <p>A tenant created here starts genuinely empty. Nothing is copied from the
 * creating operator's tenant: the only row provisioned alongside the tenant is its
 * administrator login (plus that user's home-tenant access grant), which is what
 * makes the tenant reachable at all. Every other module therefore renders its
 * empty state until data is created inside the new tenant.
 */
@Service
public class TenantAdminService {

    public static final String ACTION_CREATE = "CREATE_TENANT";
    public static final String ACTION_UPDATE = "UPDATE_TENANT";
    public static final String ACTION_DELETE = "DELETE_TENANT";

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final TenantAccessService tenantAccessService;
    private final TenantDataInventory dataInventory;
    private final TenantPurgeService purgeService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public TenantAdminService(TenantRepository tenantRepository,
                              TenantUserRepository tenantUserRepository,
                              TenantAccessService tenantAccessService,
                              TenantDataInventory dataInventory,
                              TenantPurgeService purgeService,
                              UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.tenantAccessService = tenantAccessService;
        this.dataInventory = dataInventory;
        this.purgeService = purgeService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /**
     * Tenants visible to the caller. A platform admin sees every tenant; anyone
     * else sees only their authorised set, resolved server-side.
     */
    @Transactional(readOnly = true)
    public PageResponse<TenantDto> list(AppUserPrincipal principal, String search, Pageable pageable) {
        String term = search == null || search.isBlank() ? null : search.trim();
        Page<Tenant> page;
        if (tenantAccessService.isPlatformAdmin(principal.getRole())) {
            page = tenantRepository.search(term, pageable);
        } else {
            Set<Long> allowed = tenantAccessService.accessibleTenantIds(
                    principal.getUserId(), principal.getHomeTenantId(), principal.getRole());
            if (allowed.isEmpty()) {
                return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
            }
            page = tenantRepository.searchWithin(allowed, term, pageable);
        }
        return PageResponse.from(page, tenant -> toDto(principal, tenant));
    }

    /** Single tenant, authorised for the caller. */
    @Transactional(readOnly = true)
    public TenantDto get(AppUserPrincipal principal, Long id) {
        Tenant tenant = requireVisible(principal, id);
        return toDto(principal, tenant);
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * Creates a tenant and its administrator account in one transaction.
     *
     * <p>Either both exist afterwards or neither does: if the admin account cannot
     * be created the tenant row is rolled back with it, so the list can never show
     * a tenant nobody is able to sign into.
     */
    @Transactional
    public TenantDto create(AppUserPrincipal actor, TenantCreateRequest request) {
        requirePlatformAdmin(actor);

        String name = request.name().trim();
        String adminEmail = request.adminEmail().trim().toLowerCase(java.util.Locale.ROOT);

        if (tenantRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateResourceException("Tenant name is already in use");
        }
        if (tenantRepository.existsByAdminEmailIgnoreCase(adminEmail)) {
            throw new DuplicateResourceException("Admin email is already in use");
        }

        Tenant tenant = new Tenant();
        // Temporary placeholder to satisfy non-null constraint before database ID generation
        tenant.setCompanyCode("TEMP_" + java.util.UUID.randomUUID().toString());
        tenant.setName(name);
        tenant.setCompanyName(request.companyName().trim());
        tenant.setAppName(request.companyName().trim());
        tenant.setAdminName(request.adminName().trim());
        tenant.setAdminEmail(adminEmail);
        tenant.setAdminPhone(request.adminPhone().trim());
        tenant.setStatus(TenantStatus.valueOf(request.status()));
        tenant = tenantRepository.save(tenant);

        // Auto-generate integer system identifier (matching auto-increment ID)
        tenant.setCompanyCode(String.valueOf(tenant.getId()));
        tenant = tenantRepository.save(tenant);

        // The administrator signs in via Microsoft authentication using the admin email.
        // A random UUID hash is assigned so local password sign-in is disabled.
        User admin = new User();
        admin.setTenantId(tenant.getId());
        admin.setUsername(adminEmail);
        admin.setName(request.adminName().trim());
        admin.setEmail(adminEmail);
        admin.setMobile(request.adminPhone().trim());
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        admin = userRepository.save(admin);

        tenant.setAdminUserId(admin.getId());
        tenant = tenantRepository.save(tenant);

        // The administrator's own tenant grant. No other grant is created, so the
        // new tenant is not reachable by anyone else except platform admins.
        tenantUserRepository.save(TenantUser.grant(tenant.getId(), admin.getId(), true, actor.getUserId()));

        auditService.record(actor.getTenantId(), actor.getUserId(), actor.getUsername(),
                ACTION_CREATE, "TENANT", String.valueOf(tenant.getId()), "SUCCESS",
                "Tenant " + tenant.getCompanyCode() + " created with admin " + adminEmail);

        return toDto(actor, tenant);
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    @Transactional
    public TenantDto update(AppUserPrincipal actor, Long id, TenantUpdateRequest request) {
        requirePlatformAdmin(actor);

        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        String name = request.name().trim();
        String adminEmail = request.adminEmail().trim().toLowerCase(java.util.Locale.ROOT);
        TenantStatus status = TenantStatus.valueOf(request.status());

        if (tenantRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new DuplicateResourceException("Tenant name is already in use");
        }
        if (tenantRepository.existsByAdminEmailIgnoreCaseAndIdNot(adminEmail, id)) {
            throw new DuplicateResourceException("Admin email is already in use");
        }
        // Disabling the tenant you are currently working inside would immediately
        // invalidate your own session; switch away first.
        if (status == TenantStatus.DISABLED && id.equals(actor.getTenantId())) {
            throw new BadRequestException(
                    "Switch to another tenant before disabling the tenant you are working in");
        }

        tenant.setName(name);
        tenant.setCompanyName(request.companyName().trim());
        tenant.setAdminName(request.adminName().trim());
        tenant.setAdminEmail(adminEmail);
        tenant.setAdminPhone(request.adminPhone().trim());
        tenant.setStatus(status);
        tenant = tenantRepository.save(tenant);

        syncAdminUser(tenant);

        auditService.record(actor.getTenantId(), actor.getUserId(), actor.getUsername(),
                ACTION_UPDATE, "TENANT", String.valueOf(id), "SUCCESS",
                "Tenant " + tenant.getCompanyCode() + " updated (status " + status.name() + ")");

        return toDto(actor, tenant);
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Deletes a tenant and everything it owns.
     *
     * <p>Three guards, in order of severity:
     * <ol>
     *   <li>The tenant you are currently working inside can never be deleted.</li>
     *   <li>Your own home tenant can never be deleted - it owns your login.</li>
     *   <li>A tenant still holding operational data requires the caller to echo its
     *       tenant ID back in {@code confirmTenantId}, which is the safe-confirmation
     *       step: a mis-tap cannot destroy a live fleet.</li>
     * </ol>
     */
    @Transactional
    public void delete(AppUserPrincipal actor, Long id, String confirmTenantId) {
        requirePlatformAdmin(actor);

        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        if (id.equals(actor.getTenantId())) {
            throw new BadRequestException("You cannot delete the tenant you are currently using");
        }
        if (id.equals(actor.getHomeTenantId())) {
            throw new BadRequestException("You cannot delete the tenant that owns your account");
        }

        TenantDataInventory.Snapshot snapshot = dataInventory.snapshot(id);
        if (!snapshot.isPristine()) {
            String blocking = snapshot.blockingSummary();
            if (confirmTenantId == null
                    || !confirmTenantId.trim().equalsIgnoreCase(tenant.getCompanyCode())) {
                auditService.record(actor.getTenantId(), actor.getUserId(), actor.getUsername(),
                        ACTION_DELETE, "TENANT", String.valueOf(id), "FAILURE",
                        "Unconfirmed delete of tenant holding data: " + blocking);
                throw new BadRequestException("This tenant still contains " + blocking
                        + ". Type the tenant ID \"" + tenant.getCompanyCode() + "\" to confirm deletion.");
            }
        }

        long deleted = purgeService.purge(id);

        auditService.record(actor.getTenantId(), actor.getUserId(), actor.getUsername(),
                ACTION_DELETE, "TENANT", String.valueOf(id), "SUCCESS",
                "Tenant " + tenant.getCompanyCode() + " deleted (" + deleted + " rows removed)");
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Keeps the provisioned administrator's contact details in step with the tenant. */
    private void syncAdminUser(Tenant tenant) {
        if (tenant.getAdminUserId() == null) {
            return;
        }
        User admin = userRepository.findByIdAndTenantId(tenant.getAdminUserId(), tenant.getId())
                .orElse(null);
        if (admin == null) {
            return;
        }
        admin.setName(tenant.getAdminName());
        admin.setEmail(tenant.getAdminEmail());
        admin.setMobile(tenant.getAdminPhone());
        userRepository.save(admin);
    }

    private void requirePlatformAdmin(AppUserPrincipal actor) {
        if (!tenantAccessService.isPlatformAdmin(actor.getRole())) {
            throw new ForbiddenException("Only a Super Admin can manage tenants");
        }
    }

    private Tenant requireVisible(AppUserPrincipal principal, Long id) {
        if (!tenantAccessService.canAccess(principal.getUserId(), principal.getHomeTenantId(),
                principal.getRole(), id)) {
            tenantAccessService.denied(principal, id, "Tenant not visible to this user");
            // 404 rather than 403: confirming that an unauthorised tenant exists
            // would leak the platform's tenant list.
            throw new ResourceNotFoundException("Tenant not found");
        }
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    private TenantDto toDto(AppUserPrincipal principal, Tenant tenant) {
        boolean current = tenant.getId().equals(principal.getTenantId());
        return TenantDto.of(tenant, current, deleteBlockedReason(principal, tenant));
    }

    /** The server's verdict on deletability, so the UI never offers a doomed action. */
    private String deleteBlockedReason(AppUserPrincipal principal, Tenant tenant) {
        if (!tenantAccessService.isPlatformAdmin(principal.getRole())) {
            return "Only a Super Admin can delete tenants";
        }
        if (tenant.getId().equals(principal.getTenantId())) {
            return "This is your active tenant";
        }
        if (tenant.getId().equals(principal.getHomeTenantId())) {
            return "This tenant owns your account";
        }
        return null;
    }
}
