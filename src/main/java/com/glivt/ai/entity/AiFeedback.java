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
@Table(name = "ai_feedback")
@Getter
@Setter
@NoArgsConstructor
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ai_event_id")
    private Long aiEventId;

    @Column(name = "feature_type", nullable = false, length = 48)
    private String featureType;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_correct", nullable = false)
    private boolean isCorrect;

    @Column(name = "feedback_type", nullable = false, length = 32)
    private String feedbackType = "AGREE";

    @Column(columnDefinition = "TEXT")
    private String comments;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
