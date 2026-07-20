package com.glivt.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record ReportRequest(
        @NotBlank @Size(max = 48) String reportType,
        @NotNull Instant fromTime,
        @NotNull Instant toTime,
        List<Long> deviceIds,
        List<Long> groupIds,
        List<Long> projectIds,
        List<String> eventTypes,
        Integer minimumStopMinutes,
        Integer minimumTripMinutes,
        Double minimumTripDistance,
        Boolean includeAddresses,
        Boolean includeMapMarkers,
        @Size(max = 16) String outputFormat) {
}
