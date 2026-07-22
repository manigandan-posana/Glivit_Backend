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
@Table(name = "ai_event")
@Getter
@Setter
@NoArgsConstructor
public class AiEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(nullable = false, length = 16)
    private String severity = "MEDIUM";

    @Column(nullable = false)
    private double score = 0.0;

    private Double latitude;
    private Double longitude;
    private Double speed;

    @Column(name = "deviation_path_json", columnDefinition = "TEXT")
    private String deviationPathJson;

    @Column(name = "reentry_point_json", columnDefinition = "TEXT")
    private String reentryPointJson;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(nullable = false)
    private boolean acknowledged = false;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
