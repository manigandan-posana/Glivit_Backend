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
public class DamageInspectionResponseDto {
    private Long vehicleId;
    private boolean damageDetected;
    private String severity;
    private List<String> damagedParts;
    private String repairEstimateCategory;
    private double confidence;
    private boolean requireHumanConfirmation;
    private String inspectionSummary;
    private String llmVisionAnalysis;
}
