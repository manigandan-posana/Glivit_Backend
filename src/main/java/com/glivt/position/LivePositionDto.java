package com.glivt.position;

import java.time.Instant;

/**
 * Lightweight live-position payload pushed over SSE to a tenant's subscribers.
 * Built from the authoritative {@link DeviceCurrentPosition} snapshot so the
 * stream always reflects the same in-order, validated location the REST reads
 * return.
 */
public record LivePositionDto(
        Long deviceId,
        Long vehicleId,
        double latitude,
        double longitude,
        double speed,
        double course,
        Boolean ignition,
        boolean gpsValid,
        String state,
        String address,
        Instant deviceTime,
        Instant serverTime,
        Instant updatedAt) {

    public static LivePositionDto from(DeviceCurrentPosition p) {
        return new LivePositionDto(
                p.getDeviceId(),
                p.getVehicleId(),
                p.getLatitude(),
                p.getLongitude(),
                p.getSpeed(),
                p.getCourse(),
                p.getIgnition(),
                p.isGpsValid(),
                p.getState() != null ? p.getState().name() : null,
                p.getAddress(),
                p.getDeviceTime(),
                p.getServerTime(),
                p.getUpdatedAt());
    }
}
