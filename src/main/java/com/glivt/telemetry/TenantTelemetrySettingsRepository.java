package com.glivt.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantTelemetrySettingsRepository extends JpaRepository<TenantTelemetrySettings, Long> {
}
