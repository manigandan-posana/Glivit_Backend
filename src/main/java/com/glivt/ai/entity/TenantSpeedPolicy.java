package com.glivt.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tenant speed policy. A null {@code vehicleCategory} is the tenant-wide
 * default; a set category overrides it for that vehicle type.
 *
 * <p>This exists so a speed limit reported by a GPS device is never trusted:
 * limits are always resolved server-side.
 */
@Entity
@Table(name = "tenant_speed_policy")
@Getter
@Setter
@NoArgsConstructor
public class TenantSpeedPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_category", length = 32)
    private String vehicleCategory;

    @Column(name = "speed_limit_kph", nullable = false)
    private double speedLimitKph;

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
