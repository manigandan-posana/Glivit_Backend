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
public class VehicleAiInsightDto {
    private Long vehicleId;
    private String vehicleName;
    private double overallRiskScore;
    private String riskLevel;
    private double driverScore;
    private MaintenancePredictionDto maintenancePrediction;
    private List<AiEventDto> activeAnomalies;
    private String aiVehicleSummary;
}
