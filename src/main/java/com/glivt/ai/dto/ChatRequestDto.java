package com.glivt.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A chat turn from the app.
 *
 * <p>{@code selectedVehicleId} is a hint about what the user is looking at. It is
 * re-validated against the authenticated tenant server-side, so supplying
 * another tenant's id simply resolves to no vehicle.
 */
public record ChatRequestDto(
        @NotBlank @Size(max = 2000) String message,
        @Valid @Size(max = 20) List<ChatMessageDto> history,
        @Valid EventChatContextDto eventContext,
        @Positive Long selectedVehicleId
) {}
