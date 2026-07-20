package com.glivt.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per device with the latest snapshot for fast fleet/dashboard reads. */
@Entity
@Table(name = "device_current_position")
@Getter
@Setter
@NoArgsConstructor
public class DeviceCurrentPosition {

    @Id
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private double speed = 0;

    @Column(nullable = false)
    private double course = 0;

    private Boolean ignition;

    @Column(name = "gps_valid", nullable = false)
    private boolean gpsValid = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceState state = DeviceState.NO_DATA;

    @Column(length = 512)
    private String address;

    @Column(name = "device_time")
    private Instant deviceTime;

    @Column(name = "server_time")
    private Instant serverTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
