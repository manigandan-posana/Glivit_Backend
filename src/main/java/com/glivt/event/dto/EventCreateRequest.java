package com.glivt.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record EventCreateRequest(
        @NotNull Long deviceId,
        @NotBlank @Size(max = 48) String eventType,
        @Size(max = 16) String severity,
        Double latitude,
        Double longitude,
        Double speed,
        @Size(max = 512) String address,
        Instant deviceTime,
        Instant serverTime,
        String detail) {
}
