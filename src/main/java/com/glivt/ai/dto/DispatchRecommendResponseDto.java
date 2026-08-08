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
public class DispatchRecommendResponseDto {
    private List<RankedVehicleDto> rankedVehicles;
    private String topRecommendationReason;
    /** PYTHON_AI when the ranking service answered, RULE when it degraded. */
    private String source;
    /** Always true: AI only recommends; assignment needs explicit confirmation. */
    private boolean requiresConfirmation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankedVehicleDto {
        private Long vehicleId;
        private String name;
        private double matchScore;
        private double distanceToOriginKm;
        private double etaToOriginMinutes;
        private int rank;
        private List<String> reasons;
        /** False when a hard constraint (category, availability, restriction) fails. */
        private boolean eligible;
        private Long driverId;
        private Double driverSafetyScore;
        private String maintenanceRiskLevel;
    }
}
