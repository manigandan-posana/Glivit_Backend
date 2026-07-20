package com.glivt.geofence.dto;

import com.glivt.geofence.Geofence;
import java.time.Instant;
import java.util.List;

public record GeofenceDto(
        Long id,
        String name,
        String description,
        String color,
        String type,
        List<List<Double>> coordinates,
        Double radiusMeters,
        Double corridorWidthMeters,
        List<Long> assignedDeviceIds,
        List<Long> assignedGroupIds,
        boolean enterAlert,
        boolean exitAlert,
        String activeSchedule,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static GeofenceDto from(Geofence g, List<List<Double>> coordinates,
                                   List<Long> deviceIds, List<Long> groupIds) {
        return new GeofenceDto(g.getId(), g.getName(), g.getDescription(), g.getColor(),
                g.getType(), coordinates, g.getRadiusMeters(), g.getCorridorWidthMeters(),
                deviceIds, groupIds, g.isEnterAlert(), g.isExitAlert(),
                g.getActiveSchedule(), g.isActive(), g.getCreatedAt(), g.getUpdatedAt());
    }
}
