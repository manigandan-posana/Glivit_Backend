package com.glivt.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Structured context sent when a user opens the AI command centre from an
 * event. The service re-resolves the event inside the authenticated tenant
 * before using it, so these display values are never trusted for data access.
 */
public record EventChatContextDto(
        @NotBlank @Size(max = 16) String source,
        @NotNull @Positive Long eventId,
        @NotBlank @Size(max = 160) String type,
        @NotBlank @Size(max = 160) String vehicle,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String time,
        @NotBlank @Size(max = 24) String severity,
        @NotBlank @Size(max = 512) String location,
        @NotBlank @Size(max = 2000) String description) {
}
