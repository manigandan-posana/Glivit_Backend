package com.glivt.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Append-only position history. Late / out-of-order packets are preserved here. */
@Entity
@Table(name = "positions")
@Getter
@Setter
@NoArgsConstructor
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    private Double altitude;
    private Double accuracy;

    @Column(nullable = false)
    private double speed = 0;

    @Column(nullable = false)
    private double course = 0;

    @Column(name = "device_time", nullable = false)
    private Instant deviceTime;

    @Column(name = "server_time", nullable = false)
    private Instant serverTime;

    private Boolean ignition;
    private Boolean ac;
    private Boolean door;

    @Column(name = "lock_state")
    private Boolean lockState;

    private Double battery;

    @Column(name = "external_power")
    private Double externalPower;

    @Column(name = "gps_valid", nullable = false)
    private boolean gpsValid = true;

    private Integer satellites;

    @Column(name = "network_signal")
    private Integer networkSignal;

    private Double odometer;

    @Column(name = "engine_hours")
    private Double engineHours;

    @Column(name = "fuel_level")
    private Double fuelLevel;

    @Column(name = "event_type", length = 48)
    private String eventType;

    @Column(length = 512)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String attributes;

    /**
     * Idempotency key (device-supplied message id, or a derived fallback) so
     * retried / re-transmitted packets do not create duplicate history rows.
     * Unique per device at the database level (see V3 migration).
     */
    @Column(name = "dedup_key", length = 120)
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
