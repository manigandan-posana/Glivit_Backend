package com.glivt.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    @Size(min = 5, max = 1000)
    private String jobDescription;
    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double originLat;
    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double originLng;
    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double destinationLat;
    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double destinationLng;
    @Size(max = 50)
    private String requiredCategory;
    private List<Long> candidateVehicleIds;
}
