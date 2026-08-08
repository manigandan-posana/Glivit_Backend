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

/**
 * Transactional outbox entry for asynchronous AI evaluation of a GPS position.
 *
 * <p>GPS ingestion writes this row inside its own transaction and returns
 * immediately. A bounded worker claims and processes entries, so ingestion never
 * waits on Python or Ollama and a backend restart cannot lose evaluations.
 *
 * <p>A unique index on {@code (device_id, status)} coalesces repeated positions:
 * a new packet for a device that already has a PENDING entry overwrites it, so
 * the newest valid point wins when the queue is under pressure.
 */
@Entity
@Table(name = "ai_position_outbox")
@Getter
@Setter
@NoArgsConstructor
public class AiPositionOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
