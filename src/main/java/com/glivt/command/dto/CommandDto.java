package com.glivt.command.dto;

import com.glivt.command.CommandStatus;
import com.glivt.command.DeviceCommand;
import java.time.Instant;

public record CommandDto(
        Long id,
        Long deviceId,
        String commandType,
        String payload,
        CommandStatus status,
        String idempotencyKey,
        String responseMessage,
        Instant requestedAt,
        Instant updatedAt) {

    public static CommandDto from(DeviceCommand command) {
        return new CommandDto(command.getId(), command.getDeviceId(), command.getCommandType(),
                command.getPayload(), command.getStatus(), command.getIdempotencyKey(),
                command.getResponseMessage(), command.getRequestedAt(), command.getUpdatedAt());
    }
}
