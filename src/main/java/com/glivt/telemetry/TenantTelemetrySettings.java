package com.glivt.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-tenant telemetry thresholds driving the backend device-state calculator
 * and overspeed evaluation. One row per tenant; absent row => {@link TelemetrySettings}
 * defaults.
 */
@Entity
@Table(name = "tenant_telemetry_settings")
@Getter
@Setter
@NoArgsConstructor
public class TenantTelemetrySettings {

    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "offline_timeout_seconds", nullable = false)
    private int offlineTimeoutSeconds = 900;

    @Column(name = "no_data_timeout_seconds", nullable = false)
    private int noDataTimeoutSeconds = 3600;

    @Column(name = "idle_speed_kmh", nullable = false)
    private double idleSpeedKmh = 3;

    @Column(name = "min_stop_minutes", nullable = false)
    private int minStopMinutes = 5;

    @Column(name = "overspeed_kmh", nullable = false)
    private double overspeedKmh = 80;

    @Column(name = "require_gps_valid", nullable = false)
    private boolean requireGpsValid = true;

    @Column(name = "power_min_voltage", nullable = false)
    private double powerMinVoltage = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
