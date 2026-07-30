package com.glivt.tenant;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.security.CurrentUser;
import com.glivt.tenant.dto.TenantCreateRequest;
import com.glivt.tenant.dto.TenantDto;
import com.glivt.tenant.dto.TenantSwitchRequest;
import com.glivt.tenant.dto.TenantSwitchResponse;
import com.glivt.tenant.dto.TenantUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant management and tenant switching.
 *
 * <p>Separate from {@link TenantController}, which stays a public, unauthenticated
 * company-code/branding lookup used before login. Everything here requires an
 * authenticated session, and the mutating endpoints require the SUPER_ADMIN role.
 *
 * <p>No endpoint accepts a tenant id it then trusts: {@code /{id}/switch} authorises
 * the id against the caller's tenant grants first, and every read is filtered to the
 * caller's authorised set server-side.
 */
@RestController
@RequestMapping("/api/tenants")
@Tag(name = "Tenant Management", description = "Tenant CRUD and active-tenant switching")
public class TenantAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TenantAdminService tenantAdminService;
    private final TenantSwitchService tenantSwitchService;
    private final CurrentUser currentUser;

    public TenantAdminController(TenantAdminService tenantAdminService,
                                 TenantSwitchService tenantSwitchService,
                                 CurrentUser currentUser) {
        this.tenantAdminService = tenantAdminService;
        this.tenantSwitchService = tenantSwitchService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Searchable list of the tenants the caller is authorised for")
    public ApiResponse<PageResponse<TenantDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.ASC, "name"));
        return ApiResponse.ok(tenantAdminService.list(currentUser.require(), search, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Single tenant; 404 when the caller is not authorised for it")
    public ApiResponse<TenantDto> get(@PathVariable Long id) {
        return ApiResponse.ok(tenantAdminService.get(currentUser.require(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant and its administrator account (Super Admin only)")
    public ApiResponse<TenantDto> create(@Valid @RequestBody TenantCreateRequest request) {
        return ApiResponse.ok(tenantAdminService.create(currentUser.require(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a tenant's details or status (Super Admin only)")
    public ApiResponse<TenantDto> update(@PathVariable Long id,
                                        @Valid @RequestBody TenantUpdateRequest request) {
        return ApiResponse.ok(tenantAdminService.update(currentUser.require(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tenant and all of its data (Super Admin only). "
            + "A tenant holding operational data requires confirmTenantId to match its tenant ID.")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(required = false) String confirmTenantId) {
        tenantAdminService.delete(currentUser.require(), id, confirmTenantId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/switch")
    @Operation(summary = "Make a tenant the caller's active tenant and return a rebound session")
    public ApiResponse<TenantSwitchResponse> switchTenant(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TenantSwitchRequest request) {
        String deviceInfo = request == null ? null : request.deviceInfo();
        return ApiResponse.ok(tenantSwitchService.switchTo(currentUser.require(), id, deviceInfo));
    }
}
