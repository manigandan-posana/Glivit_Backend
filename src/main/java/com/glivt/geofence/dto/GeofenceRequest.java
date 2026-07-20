package com.glivt.geofence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record GeofenceRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 512) String description,
        @Size(max = 9) String color,
        @NotBlank @Size(max = 16) String type,
        @NotNull List<List<Double>> coordinates,
        Double radiusMeters,
        Double corridorWidthMeters,
        List<Long> assignedDeviceIds,
        List<Long> assignedGroupIds,
        Boolean enterAlert,
        Boolean exitAlert,
        @Size(max = 256) String activeSchedule,
        Boolean active) {
}
