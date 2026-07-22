package com.glivt.ai.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDashboardSummaryDto {
    private double fleetHealthScore;
    private long totalActiveVehicles;
    private long unacknowledgedAiAlerts;
    private long criticalRiskVehicles;
    private long highRiskMaintenanceCount;
    private long riskyDriversCount;
    private long activeRouteDeviationsCount;
    private List<AiEventDto> recentCriticalEvents;
    private String executiveAiSummary;
}
