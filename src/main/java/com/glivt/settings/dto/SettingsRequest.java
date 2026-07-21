package com.glivt.settings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SettingsRequest(
        @Size(max = 8) String distanceUnit,
        @Size(max = 8) String speedUnit,
        @Size(max = 16) String timeFormat,
        @Size(max = 24) String mapStyle,
        Boolean trafficEnabled,
        @Size(max = 24) String routeColorMode,
        Boolean notificationSound,
        @Size(max = 16) String language,
        @Size(max = 24) String dateFormat,
        @Size(max = 24) String defaultHistoryRange,
        Boolean autoFollowVehicle,
        @Min(5) @Max(3600) Integer refreshFrequencySeconds,
        @Size(max = 2000) String privacyOptions) {
}
