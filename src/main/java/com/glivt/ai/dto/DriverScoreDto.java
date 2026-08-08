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
    private String riskLevel;
    private String reasonsJson;
    /** PYTHON_AI | RULE | NONE - never claims a model produced a rule result. */
    private String source;
    private String ruleVersion;
    private String modelVersion;
    private java.time.Instant calculatedAt;
    /** False when no score has been calculated for this driver yet. */
    private boolean hasScore;
}
