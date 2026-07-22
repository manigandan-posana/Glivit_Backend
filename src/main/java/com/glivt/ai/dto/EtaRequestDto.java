package com.glivt.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtaRequestDto {
    @NotNull
    private Long vehicleId;
    @NotNull
    private Double originLat;
    @NotNull
    private Double originLng;
    @NotNull
    private Double destinationLat;
    @NotNull
    private Double destinationLng;
    private Double currentSpeedKph;
}
