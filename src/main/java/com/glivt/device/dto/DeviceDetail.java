package com.glivt.device.dto;

import java.time.Instant;
import java.time.LocalDate;

public record DeviceDetail(
        Long id,
        String name,
        String imei,
        String model,
        String category,
        Long projectId,
        Long groupId,
        Long vehicleId,
        Long managerId,
        String simNumber,
        String simProvider,
        String simApn,
        String driverName,
        String driverPhone,
        String address,
        String remarks,
        LocalDate expiryDate,
        LocalDate activatedAt,
        String timezone,
        String distanceUnit,
        String speedUnit,
        String status,
        String state,
        Double latitude,
        Double longitude,
        double speed,
        double course,
        Boolean ignition,
        boolean gpsValid,
        Instant lastUpdate) {
}
