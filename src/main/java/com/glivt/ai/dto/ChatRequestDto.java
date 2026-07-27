package com.glivt.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequestDto(
        @NotBlank @Size(max = 2000) String message,
        @Valid @Size(max = 20) List<ChatMessageDto> history,
        @Valid EventChatContextDto eventContext
) {}
