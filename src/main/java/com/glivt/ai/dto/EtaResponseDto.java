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
}
