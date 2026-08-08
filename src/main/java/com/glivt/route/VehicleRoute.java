package com.glivt.route;

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
 * A planned route corridor. {@code pathJson} is an ordered polyline
 * {@code [[lat,lng],...]}; route deviation is the shortest distance from the
 * vehicle's snapped position to that polyline.
 */
@Entity
@Table(name = "vehicle_route")
@Getter
@Setter
@NoArgsConstructor
public class VehicleRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(name = "path_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pathJson;

    @Column(name = "allowed_deviation_meters", nullable = false)
    private double allowedDeviationMeters = 500.0;

    /** Route-specific speed rule; the highest-priority speed-limit source. */
    @Column(name = "speed_limit_kph")
    private Double speedLimitKph;

    @Column(nullable = false)
    private boolean active = true;

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
