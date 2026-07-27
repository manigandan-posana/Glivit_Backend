package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageDto(
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 2000) String content,
        @Size(max = 64) String timestamp
) {}
