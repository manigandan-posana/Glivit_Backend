package com.glivt.device.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Compact device row for list / map screens (device + current position). */
public record DeviceSummary(
        Long id,
        String name,
        String imei,
        String category,
        Long vehicleId,
        String state,
        Double latitude,
        Double longitude,
        double speed,
        double course,
        Boolean ignition,
        boolean gpsValid,
        String address,
        Instant lastUpdate,
        LocalDate expiryDate,
        String status) {
}
