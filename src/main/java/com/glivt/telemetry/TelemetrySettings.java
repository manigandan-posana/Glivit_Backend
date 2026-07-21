package com.glivt.telemetry;

import java.time.Duration;

/**
 * Immutable, resolved telemetry thresholds for a tenant (persisted overrides
 * merged with application defaults). Consumed by the device-state calculator.
 */
public record TelemetrySettings(
        int offlineTimeoutSeconds,
        int noDataTimeoutSeconds,
        double idleSpeedKmh,
        int minStopMinutes,
        double overspeedKmh,
        boolean requireGpsValid,
        double powerMinVoltage) {

    public static TelemetrySettings defaults() {
        return new TelemetrySettings(900, 3600, 3, 5, 80, true, 1);
    }

    public static TelemetrySettings from(TenantTelemetrySettings e) {
        return new TelemetrySettings(
                e.getOfflineTimeoutSeconds(),
                e.getNoDataTimeoutSeconds(),
                e.getIdleSpeedKmh(),
                e.getMinStopMinutes(),
                e.getOverspeedKmh(),
                e.isRequireGpsValid(),
                e.getPowerMinVoltage());
    }

    public Duration offlineTimeout() {
        return Duration.ofSeconds(offlineTimeoutSeconds);
    }
}
