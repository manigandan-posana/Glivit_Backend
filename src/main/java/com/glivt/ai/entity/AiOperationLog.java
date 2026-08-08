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
 * Governance record for every AI result: what produced it, with which model,
 * prompt and rule version, how long it took and why it fell back.
 *
 * <p>This is what makes a stored prediction reproducible and lets an evaluation
 * report be built from user feedback. Feedback is never used to retrain or to
 * move a production threshold automatically.
 */
@Entity
@Table(name = "ai_operation_log")
@Getter
@Setter
@NoArgsConstructor
public class AiOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 64)
    private String operation;

    /** PYTHON_AI | OLLAMA | RULE | MODEL */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "model_name", length = 96)
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "rule_version", length = 64)
    private String ruleVersion;

    @Column(name = "processing_ms", nullable = false)
    private long processingMs;

    @Column(name = "fallback_reason", length = 64)
    private String fallbackReason;

    @Column(name = "reference_type", length = 32)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
