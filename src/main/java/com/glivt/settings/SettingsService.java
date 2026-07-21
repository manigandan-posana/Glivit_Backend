package com.glivt.settings;

import com.glivt.audit.AuditService;
import com.glivt.settings.dto.SettingsDto;
import com.glivt.settings.dto.SettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private final UserSettingsRepository repository;
    private final AuditService auditService;

    public SettingsService(UserSettingsRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public SettingsDto get(Long tenantId, Long userId) {
        return SettingsDto.from(findOrCreate(tenantId, userId));
    }

    @Transactional
    public SettingsDto update(Long tenantId, Long userId, String username, SettingsRequest request) {
        UserSettings settings = findOrCreate(tenantId, userId);
        if (request.distanceUnit() != null) settings.setDistanceUnit(request.distanceUnit());
        if (request.speedUnit() != null) settings.setSpeedUnit(request.speedUnit());
        if (request.timeFormat() != null) settings.setTimeFormat(request.timeFormat());
        if (request.mapStyle() != null) settings.setMapStyle(request.mapStyle());
        if (request.trafficEnabled() != null) settings.setTrafficEnabled(request.trafficEnabled());
        if (request.routeColorMode() != null) settings.setRouteColorMode(request.routeColorMode());
        if (request.notificationSound() != null) settings.setNotificationSound(request.notificationSound());
        if (request.language() != null) settings.setLanguage(request.language());
        if (request.dateFormat() != null) settings.setDateFormat(request.dateFormat());
        if (request.defaultHistoryRange() != null) settings.setDefaultHistoryRange(request.defaultHistoryRange());
        if (request.autoFollowVehicle() != null) settings.setAutoFollowVehicle(request.autoFollowVehicle());
        if (request.refreshFrequencySeconds() != null) {
            settings.setRefreshFrequencySeconds(request.refreshFrequencySeconds());
        }
        if (request.privacyOptions() != null) settings.setPrivacyOptions(request.privacyOptions());
        settings = repository.save(settings);
        auditService.record(tenantId, userId, username, "UPDATE_SETTINGS", "USER",
                String.valueOf(userId), "SUCCESS", null);
        return SettingsDto.from(settings);
    }

    private UserSettings findOrCreate(Long tenantId, Long userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId).orElseGet(() -> {
            UserSettings settings = new UserSettings();
            settings.setTenantId(tenantId);
            settings.setUserId(userId);
            return repository.save(settings);
        });
    }
}
