package com.glivt.ai.entity;

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
 * Persistent motion state per device.
 *
 * <p>Continuous stationary duration cannot be derived from a single packet gap:
 * that value can never exceed the reporting interval, so the 15-minute idling
 * threshold was unreachable. This row accumulates stationary time across packets
 * and survives restarts, and is reset the moment real movement is seen.
 *
 * <p>It also carries telemetry-quality counters. Out-of-order and duplicate
 * packets are recorded here as a low-level quality metric instead of being fed
 * to anomaly scoring, where they produced false positives.
 */
@Entity
@Table(name = "device_motion_state")
@Getter
@Setter
@NoArgsConstructor
public class DeviceMotionState {

    /** The device id is the primary key: exactly one state row per device. */
    @Id
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    /** When the current continuous stationary period began; null while moving. */
    @Column(name = "stationary_since")
    private Instant stationarySince;

    @Column(name = "last_movement_at")
    private Instant lastMovementAt;

    @Column(name = "continuous_stationary_seconds", nullable = false)
    private double continuousStationarySeconds = 0.0;

    @Column(name = "ignition_on")
    private Boolean ignitionOn;

    @Column(name = "previously_moving", nullable = false)
    private boolean previouslyMoving = false;

    /** Device time of the newest in-order packet seen. Guards ordering. */
    @Column(name = "last_device_time")
    private Instant lastDeviceTime;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "out_of_order_packets", nullable = false)
    private long outOfOrderPackets = 0;

    @Column(name = "duplicate_packets", nullable = false)
    private long duplicatePackets = 0;

    @Column(name = "low_confidence_packets", nullable = false)
    private long lowConfidencePackets = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
