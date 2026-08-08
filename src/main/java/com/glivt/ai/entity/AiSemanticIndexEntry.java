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
 * One indexed, tenant-owned record available to semantic search.
 *
 * <p>Every row carries {@code tenantId} plus the vehicle and driver it belongs
 * to, so search can filter by tenant *and* by the caller's vehicle assignments
 * before anything is ranked. The embedding is stored as a JSON array; MySQL has
 * no native vector type here, and fleet-sized corpora rank fine in memory after
 * the tenant filter has been applied.
 */
@Entity
@Table(name = "ai_semantic_index")
@Getter
@Setter
@NoArgsConstructor
public class AiSemanticIndexEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** AI_EVENT | TRIP | ALERT | MAINTENANCE | ROUTE_DEVIATION | VEHICLE_NOTE */
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "embedding_json", columnDefinition = "LONGTEXT")
    private String embeddingJson;

    @Column(name = "embedding_model", length = 96)
    private String embeddingModel;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @Column(name = "occurred_at")
    private Instant occurredAt;

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
