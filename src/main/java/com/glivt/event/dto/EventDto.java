package com.glivt.event.dto;

import com.glivt.event.Event;
import java.time.Instant;

public record EventDto(
        Long id,
        Long deviceId,
        Long vehicleId,
        String eventType,
        String severity,
        Double latitude,
        Double longitude,
        Double speed,
        String address,
        Instant deviceTime,
        Instant serverTime,
        boolean acknowledged,
        Instant acknowledgedAt,
        String detail) {

    public static EventDto from(Event e) {
        return new EventDto(e.getId(), e.getDeviceId(), e.getVehicleId(), e.getEventType(),
                e.getSeverity(), e.getLatitude(), e.getLongitude(), e.getSpeed(),
                e.getAddress(), e.getDeviceTime(), e.getServerTime(), e.isAcknowledged(),
                e.getAcknowledgedAt(), e.getDetail());
    }
}
