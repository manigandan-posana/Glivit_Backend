package com.glivt.command.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CommandRequest(
        @NotNull Long deviceId,
        @NotBlank @Size(max = 64) String commandType,
        @Size(max = 4000) String payload,
        @NotBlank @Size(max = 96) String idempotencyKey,
        Boolean confirmed) {
}
