package com.glivt.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(nullable = false, unique = true, length = 64)
    private String imei;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "sim_number", length = 32)
    private String simNumber;

    @Column(length = 64)
    private String model;

    private Integer port;

    @Column(nullable = false, length = 32)
    private String category = "GPS";

    @Column(name = "driver_name", length = 160)
    private String driverName;

    @Column(name = "driver_phone", length = 32)
    private String driverPhone;

    @Column(length = 512)
    private String remarks;

    @Column(length = 512)
    private String address;

    @Column(name = "sim_provider", length = 64)
    private String simProvider;

    @Column(name = "sim_apn", length = 64)
    private String simApn;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "activated_at")
    private LocalDate activatedAt;

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Kolkata";

    @Column(name = "distance_unit", nullable = false, length = 8)
    private String distanceUnit = "KM";

    @Column(name = "speed_unit", nullable = false, length = 8)
    private String speedUnit = "KMH";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    /** Opaque per-device ingestion credential (null until first issued). */
    @Column(name = "ingest_token", length = 80)
    private String ingestToken;

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
