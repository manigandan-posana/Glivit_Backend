package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequestDto {
    private Long aiEventId;
    @NotBlank
    @Size(max = 50)
    private String featureType;
    @NotNull
    private Boolean isCorrect;
    @Pattern(regexp = "^(AGREE|DISAGREE|FALSE_POSITIVE|FALSE_NEGATIVE)$", message = "Invalid feedback type")
    private String feedbackType; // AGREE, DISAGREE, FALSE_POSITIVE, FALSE_NEGATIVE
    @Size(max = 2000)
    private String comments;
}
