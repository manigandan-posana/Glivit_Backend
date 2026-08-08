package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(min = 2, max = 500)
    private String query;
    @Builder.Default
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(100)
    private int limit = 10;
    /** Matches below this score are discarded, so an unrelated search returns nothing. */
    private Double minScore;
}
