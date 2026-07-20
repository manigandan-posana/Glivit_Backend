package com.glivt.report.dto;

import com.glivt.report.ReportJob;
import com.glivt.report.ReportStatus;
import java.time.Instant;

public record ReportDto(
        Long id,
        String reportType,
        ReportStatus status,
        Instant fromTime,
        Instant toTime,
        String outputFormat,
        String fileName,
        Long fileSize,
        String downloadUrl,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {

    public static ReportDto from(ReportJob job) {
        return new ReportDto(job.getId(), job.getReportType(), job.getStatus(),
                job.getFromTime(), job.getToTime(), job.getOutputFormat(),
                job.getFileName(), job.getFileSize(), job.getDownloadUrl(),
                job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt());
    }
}
