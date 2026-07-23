package com.glivt.ingest;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Raw GPS packet as sent by a tracker/simulator. Note there is intentionally NO
 * tenantId/deviceId/vehicleId here — those are resolved server-side from the
 * device token so a device can never assert another tenant's identity.
 */
public record IngestPositionRequest(
        @NotNull Double latitude,
        @NotNull Double longitude,
        Double speedKph,
        Double heading,
        Double altitude,
        Double accuracyMeters,
        Boolean ignitionOn,
        Double batteryLevel,
        Double externalPower,
        Double odometerKm,
        Double engineHours,
        Double fuelLevel,
        Instant recordedAt,
        Integer satelliteCount,
        Integer networkSignal,
        Long sequenceNumber,
        Double speedLimitKph,
        String eventType) {
}
