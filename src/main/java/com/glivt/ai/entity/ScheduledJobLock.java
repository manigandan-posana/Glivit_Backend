package com.glivt.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Database-backed lease so a scheduled job runs once across all backend
 * instances. The lease expires, so a crashed holder never blocks the job
 * permanently.
 */
@Entity
@Table(name = "scheduled_job_lock")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledJobLock {

    @Id
    @Column(name = "job_name", length = 96)
    private String jobName;

    @Column(name = "locked_by", nullable = false, length = 128)
    private String lockedBy;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
