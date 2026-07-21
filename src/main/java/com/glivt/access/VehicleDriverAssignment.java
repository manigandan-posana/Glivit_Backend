package com.glivt.access;

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

/** Vehicle&lt;-&gt;driver assignment (historical + current) used for access scoping. */
@Entity
@Table(name = "vehicle_driver_assignments")
@Getter
@Setter
@NoArgsConstructor
public class VehicleDriverAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 32)
    private String source = "MANUAL";

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "start_time", nullable = false)
    private Instant startTime = Instant.now();

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.startTime == null) {
            this.startTime = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
