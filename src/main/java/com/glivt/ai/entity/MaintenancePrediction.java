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
@Table(name = "maintenance_prediction")
@Getter
@Setter
@NoArgsConstructor
public class MaintenancePrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "risk_score", nullable = false)
    private double riskScore = 0.0;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel = "LOW";

    @Column(name = "predicted_failure_date")
    private LocalDate predictedFailureDate;

    @Column(name = "predicted_days_remaining")
    private Integer predictedDaysRemaining;

    @Column(name = "odometer_at_prediction", nullable = false)
    private double odometerAtPrediction = 0.0;

    @Column(name = "engine_hours_at_prediction", nullable = false)
    private double engineHoursAtPrediction = 0.0;

    @Column(name = "battery_health", nullable = false)
    private double batteryHealth = 100.0;

    @Column(name = "driving_stress_factor", nullable = false)
    private double drivingStressFactor = 1.0;

    @Column(name = "recommended_action", columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(nullable = false, length = 24)
    private String status = "PENDING";

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
