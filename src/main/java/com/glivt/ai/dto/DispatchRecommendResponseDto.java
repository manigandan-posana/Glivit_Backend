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
    }
}
