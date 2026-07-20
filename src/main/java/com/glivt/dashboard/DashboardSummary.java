package com.glivt.dashboard;

import java.time.Instant;
import java.util.Map;

/** Fleet status totals for the dashboard chart and status cards. */
public record DashboardSummary(
        Map<String, Long> counts,
        long total,
        Instant lastUpdated) {
}
