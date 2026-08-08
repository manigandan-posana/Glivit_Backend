package com.glivt.ai.dto;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtaResponseDto {
    private Long vehicleId;
    private double estimatedDistanceKm;
    private double estimatedDurationMinutes;
    private Instant predictedArrivalTime;
    private double trafficDelayMinutes;
    private double confidence;
    private Map<String, Object> factors;
    private String structuredExplanation;
    /** PYTHON_AI/MODEL when the AI service answered, RULE when it degraded. */
    private String source;
    /** ROAD_ROUTE or STRAIGHT_LINE_ADJUSTED - never implies routing that did not happen. */
    private String distanceSource;
    /** LIVE, TIME_OF_DAY_PROFILE or NONE. */
    private String trafficInput;
    /** Plus/minus confidence band around the estimate, in minutes. */
    private double rangeMinutes;
    private double lateProbability;
    private Instant calculatedAt;
}
