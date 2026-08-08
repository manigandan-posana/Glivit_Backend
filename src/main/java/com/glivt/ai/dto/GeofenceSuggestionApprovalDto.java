package com.glivt.ai.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional edits a reviewer applies before approving an AI geofence suggestion.
 * Both fields may be omitted to accept the suggestion as generated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeofenceSuggestionApprovalDto {

    @Size(max = 160)
    private String name;

    @Positive
    private Double radiusMeters;
}
