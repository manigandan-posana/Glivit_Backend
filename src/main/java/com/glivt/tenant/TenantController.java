package com.glivt.tenant;

import com.glivt.common.ApiResponse;
import com.glivt.tenant.dto.CompanyCodeRequest;
import com.glivt.tenant.dto.TenantConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant")
@Tag(name = "Tenant", description = "White-label company-code resolution and branding config")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/resolve")
    @Operation(summary = "Validate a company code and return its white-label configuration")
    public ApiResponse<TenantConfigResponse> resolve(@Valid @RequestBody CompanyCodeRequest request) {
        return ApiResponse.ok(tenantService.resolve(request.companyCode()));
    }

    @GetMapping("/{companyCode}/config")
    @Operation(summary = "Fetch cached branding configuration for a company code")
    public ApiResponse<TenantConfigResponse> config(@PathVariable String companyCode) {
        return ApiResponse.ok(tenantService.resolve(companyCode));
    }
}
