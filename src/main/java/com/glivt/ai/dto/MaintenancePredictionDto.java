package com.glivt.ai.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePredictionDto {
    private Long id;
    private Long vehicleId;
    private String vehicleName;
    private double riskScore;
    private String riskLevel;
    private LocalDate predictedFailureDate;
    private Integer predictedDaysRemaining;
    private double odometerAtPrediction;
    private double engineHoursAtPrediction;
    private double batteryHealth;
    private double drivingStressFactor;
    private List<String> recommendedActions;
    private String reasoning;
    private String status;
}
