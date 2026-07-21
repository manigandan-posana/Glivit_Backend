package com.glivt.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "distance_unit", nullable = false, length = 8)
    private String distanceUnit = "KM";

    @Column(name = "speed_unit", nullable = false, length = 8)
    private String speedUnit = "KMH";

    @Column(name = "time_format", nullable = false, length = 16)
    private String timeFormat = "24H";

    @Column(name = "map_style", nullable = false, length = 24)
    private String mapStyle = "street";

    @Column(name = "traffic_enabled", nullable = false)
    private boolean trafficEnabled = false;

    @Column(name = "route_color_mode", nullable = false, length = 24)
    private String routeColorMode = "speed";

    @Column(name = "notification_sound", nullable = false)
    private boolean notificationSound = true;

    @Column(nullable = false, length = 16)
    private String language = "en";

    @Column(name = "date_format", nullable = false, length = 24)
    private String dateFormat = "dd-MMM-yyyy";

    @Column(name = "default_history_range", nullable = false, length = 24)
    private String defaultHistoryRange = "today";

    @Column(name = "auto_follow_vehicle", nullable = false)
    private boolean autoFollowVehicle = true;

    @Column(name = "refresh_frequency_sec", nullable = false)
    private int refreshFrequencySeconds = 30;

    @Column(name = "privacy_options", columnDefinition = "TEXT")
    private String privacyOptions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
