package com.glivt.position.dto;

import com.glivt.position.Position;
import java.time.Instant;

/** A single history row for the positions API. */
public record PositionDto(
        long id,
        Instant deviceTime,
        Instant serverTime,
        double latitude,
        double longitude,
        double speed,
        double course,
        Boolean ignition,
        boolean gpsValid,
        Integer satellites,
        Integer networkSignal,
        Double fuelLevel,
        String eventType,
        String address) {

    public static PositionDto from(Position p) {
        return new PositionDto(
                p.getId(), p.getDeviceTime(), p.getServerTime(),
                p.getLatitude(), p.getLongitude(), p.getSpeed(), p.getCourse(),
                p.getIgnition(), p.isGpsValid(), p.getSatellites(), p.getNetworkSignal(),
                p.getFuelLevel(), p.getEventType(), p.getAddress());
    }
}
