package com.glivt.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
public class ReportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "report_type", nullable = false, length = 48)
    private String reportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReportStatus status = ReportStatus.QUEUED;

    @Column(name = "from_time", nullable = false)
    private Instant fromTime;

    @Column(name = "to_time", nullable = false)
    private Instant toTime;

    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    @Column(name = "output_format", nullable = false, length = 16)
    private String outputFormat = "CSV";

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "download_url", length = 512)
    private String downloadUrl;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
