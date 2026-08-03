package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticSearchRequestDto {
    @NotBlank
    private String query;
    @Builder.Default
    private int limit = 10;
}
