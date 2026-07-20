package com.glivt.tenant;

import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.tenant.dto.TenantConfigResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public TenantConfigResponse resolve(String companyCode) {
        Tenant tenant = tenantRepository.findByCompanyCodeIgnoreCase(companyCode)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid company code"));
        return toConfig(tenant);
    }

    private TenantConfigResponse toConfig(Tenant tenant) {
        return new TenantConfigResponse(
                tenant.getCompanyCode(),
                tenant.getName(),
                tenant.getAppName(),
                tenant.getLogoUrl(),
                tenant.getSplashImageUrl(),
                tenant.getPrimaryColor(),
                tenant.getSecondaryColor(),
                tenant.getSupportPhone(),
                tenant.getSupportEmail(),
                tenant.getPrivacyPolicyUrl(),
                tenant.getTermsUrl(),
                parseModules(tenant.getEnabledModules()),
                tenant.isPaymentEnabled(),
                tenant.getMaxHistoryDays(),
                tenant.getMinAppVersion(),
                tenant.getStatus().name());
    }

    private List<String> parseModules(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
