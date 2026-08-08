package com.glivt.ai.entity;

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

@Entity
@Table(name = "geofence_suggestion")
@Getter
@Setter
@NoArgsConstructor
public class GeofenceSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "suggested_name", nullable = false, length = 160)
    private String suggestedName;

    @Column(name = "center_latitude", nullable = false)
    private double centerLatitude;

    @Column(name = "center_longitude", nullable = false)
    private double centerLongitude;

    @Column(name = "suggested_radius_meters", nullable = false)
    private double suggestedRadiusMeters = 200.0;

    @Column(name = "cluster_point_count", nullable = false)
    private int clusterPointCount = 0;

    @Column(nullable = false)
    private double confidence = 0.0;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "polygon_json", columnDefinition = "TEXT")
    private String polygonJson;

    /** PENDING | APPROVED | DISMISSED */
    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    // Visit statistics a reviewer needs before approving the suggestion.

    @Column(name = "visit_count", nullable = false)
    private int visitCount = 0;

    @Column(name = "average_stop_minutes", nullable = false)
    private double averageStopMinutes = 0.0;

    @Column(name = "first_visit_at")
    private Instant firstVisitAt;

    @Column(name = "last_visit_at")
    private Instant lastVisitAt;

    @Column(name = "distinct_vehicle_count", nullable = false)
    private int distinctVehicleCount = 0;

    @Column(name = "dismissed_by")
    private Long dismissedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
