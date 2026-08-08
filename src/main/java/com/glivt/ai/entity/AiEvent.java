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

    // ------------------------------------------------------------------
    // Incident lifecycle. An AI event is an *incident*, not a row per GPS
    // packet: continuous speeding updates one OPEN incident rather than
    // inserting thousands of near-identical rows.
    // ------------------------------------------------------------------

    /** Deterministic dedup key: tenant + vehicle + type + coarse context. */
    @Column(length = 96)
    private String fingerprint;

    /** OPEN | ACKNOWLEDGED | RESOLVED */
    @Column(nullable = false, length = 24)
    private String status = STATUS_OPEN;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    @Column(name = "first_observed_at")
    private Instant firstObservedAt;

    @Column(name = "last_observed_at")
    private Instant lastObservedAt;

    /** Highest score seen for this incident; drives severity escalation. */
    @Column(name = "max_score", nullable = false)
    private double maxScore = 0.0;

    /** Comma-separated secondary event types folded into this incident. */
    @Column(name = "related_event_types", length = 512)
    private String relatedEventTypes;

    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "distance_from_route_meters")
    private Double distanceFromRouteMeters;

    @Column(name = "speed_limit_kph")
    private Double speedLimitKph;

    @Column(name = "speed_limit_source", length = 32)
    private String speedLimitSource;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    // -- provenance -----------------------------------------------------

    @Column(nullable = false, length = 32)
    private String source = "RULE";

    @Column(name = "model_name", length = 96)
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "rule_version", length = 64)
    private String ruleVersion;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "processing_ms")
    private Long processingMs;

    @Column(name = "fallback_reason", length = 64)
    private String fallbackReason;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.firstObservedAt == null) {
            this.firstObservedAt = this.createdAt;
        }
        if (this.lastObservedAt == null) {
            this.lastObservedAt = this.createdAt;
        }
        if (this.maxScore < this.score) {
            this.maxScore = this.score;
        }
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
