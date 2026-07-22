package com.glivt.ai.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverScoreDto {
    private Long id;
    private Long driverId;
    private String driverName;
    private Long vehicleId;
    private LocalDate scoreDate;
    private String scorePeriod;
    private double safetyScore;
    private double efficiencyScore;
    private double complianceScore;
    private double overallScore;
    private String grade;
    private double totalDistanceKm;
    private int totalDrivingMinutes;
    private int harshAccelCount;
    private int harshBrakeCount;
    private int sharpTurnCount;
    private int speedingSeconds;
    private int excessiveIdleMinutes;
    private int anomaliesCount;
    private String breakdownJson;
    private String aiCoachingAdvice;
}
