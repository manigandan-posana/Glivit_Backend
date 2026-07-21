package com.glivt.telemetry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves per-tenant telemetry thresholds, falling back to application defaults. */
@Service
public class TelemetrySettingsService {

    private final TenantTelemetrySettingsRepository repository;

    public TelemetrySettingsService(TenantTelemetrySettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public TelemetrySettings resolve(Long tenantId) {
        return repository.findById(tenantId)
                .map(TelemetrySettings::from)
                .orElseGet(TelemetrySettings::defaults);
    }
}
