package com.glivt.device.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record DeviceUpsertRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 64) String imei,
        @Size(max = 32) String simNumber,
        @Size(max = 64) String model,
        Integer port,
        @NotBlank @Size(max = 32) String category,
        Long projectId,
        Long groupId,
        Long vehicleId,
        Long managerId,
        @Size(max = 160) String driverName,
        @Pattern(regexp = "^[+0-9 ()-]{0,32}$", message = "Invalid phone number")
        String driverPhone,
        @Size(max = 512) String remarks,
        @Size(max = 512) String address,
        @Size(max = 64) String simProvider,
        @Size(max = 64) String simApn,
        @FutureOrPresent(message = "Expiry date cannot be in the past") LocalDate expiryDate,
        LocalDate activatedAt,
        @Size(max = 64) String timezone,
        @Size(max = 8) String distanceUnit,
        @Size(max = 8) String speedUnit) {
}
