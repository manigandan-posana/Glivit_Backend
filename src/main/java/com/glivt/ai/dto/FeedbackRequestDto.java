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
public class FeedbackRequestDto {
    private Long aiEventId;
    @NotNull
    private String featureType;
    @NotNull
    private Boolean isCorrect;
    private String feedbackType; // AGREE, DISAGREE, FALSE_POSITIVE, FALSE_NEGATIVE
    private String comments;
}
