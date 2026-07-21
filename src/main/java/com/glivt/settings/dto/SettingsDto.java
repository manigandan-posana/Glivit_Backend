package com.glivt.settings.dto;

import com.glivt.settings.UserSettings;
import java.time.Instant;

public record SettingsDto(
        String distanceUnit,
        String speedUnit,
        String timeFormat,
        String mapStyle,
        boolean trafficEnabled,
        String routeColorMode,
        boolean notificationSound,
        String language,
        String dateFormat,
        String defaultHistoryRange,
        boolean autoFollowVehicle,
        int refreshFrequencySeconds,
        String privacyOptions,
        Instant updatedAt) {

    public static SettingsDto from(UserSettings s) {
        return new SettingsDto(s.getDistanceUnit(), s.getSpeedUnit(), s.getTimeFormat(),
                s.getMapStyle(), s.isTrafficEnabled(), s.getRouteColorMode(),
                s.isNotificationSound(), s.getLanguage(), s.getDateFormat(),
                s.getDefaultHistoryRange(), s.isAutoFollowVehicle(),
                s.getRefreshFrequencySeconds(), s.getPrivacyOptions(), s.getUpdatedAt());
    }
}
