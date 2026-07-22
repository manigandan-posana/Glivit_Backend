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
public class DamageInspectionRequestDto {
    @NotNull
    private Long vehicleId;
    private String imageBase64;
    private String imageUrl;
    private String notes;
}
