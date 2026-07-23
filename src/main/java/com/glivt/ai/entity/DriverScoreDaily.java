package com.glivt.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "driver_score_daily")
@Getter
@Setter
@NoArgsConstructor
public class DriverScoreDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "score_date", nullable = false)
    private LocalDate scoreDate;

    @Column(name = "score_period", nullable = false, length = 16)
    private String scorePeriod = "DAILY";

    @Column(name = "safety_score", nullable = false)
    private double safetyScore = 100.0;

    @Column(name = "efficiency_score", nullable = false)
    private double efficiencyScore = 100.0;

    @Column(name = "compliance_score", nullable = false)
    private double complianceScore = 100.0;

    @Column(name = "overall_score", nullable = false)
    private double overallScore = 100.0;

    @Column(name = "total_distance_km", nullable = false)
    private double totalDistanceKm = 0.0;

    @Column(name = "total_driving_minutes", nullable = false)
    private int totalDrivingMinutes = 0;

    @Column(name = "harsh_accel_count", nullable = false)
    private int harshAccelCount = 0;

    @Column(name = "harsh_brake_count", nullable = false)
    private int harshBrakeCount = 0;

    @Column(name = "sharp_turn_count", nullable = false)
    private int sharpTurnCount = 0;

    @Column(name = "speeding_seconds", nullable = false)
    private int speedingSeconds = 0;

    @Column(name = "excessive_idle_minutes", nullable = false)
    private int excessiveIdleMinutes = 0;

    @Column(name = "anomalies_count", nullable = false)
    private int anomaliesCount = 0;

    @Column(name = "breakdown_json", columnDefinition = "TEXT")
    private String breakdownJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
