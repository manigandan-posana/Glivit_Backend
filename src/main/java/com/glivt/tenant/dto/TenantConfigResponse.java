package com.glivt.tenant.dto;

import java.util.List;

/** Public white-label configuration downloaded after the company code resolves. */
public record TenantConfigResponse(
        String companyCode,
        String name,
        String appName,
        String logoUrl,
        String splashImageUrl,
        String primaryColor,
        String secondaryColor,
        String supportPhone,
        String supportEmail,
        String privacyPolicyUrl,
        String termsUrl,
        List<String> enabledModules,
        boolean paymentEnabled,
        int maxHistoryDays,
        String minAppVersion,
        String status) {
}
