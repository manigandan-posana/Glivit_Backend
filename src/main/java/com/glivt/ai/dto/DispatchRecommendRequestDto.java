package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchRecommendRequestDto {
    @NotBlank
    private String jobDescription;
    @NotNull
    private Double originLat;
    @NotNull
    private Double originLng;
    @NotNull
    private Double destinationLat;
    @NotNull
    private Double destinationLng;
    private String requiredCategory;
    private List<Long> candidateVehicleIds;
}
