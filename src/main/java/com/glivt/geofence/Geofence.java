package com.glivt.geofence;

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
@Table(name = "geofences")
@Getter
@Setter
@NoArgsConstructor
public class Geofence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 9)
    private String color = "#27D34D";

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "coordinates_json", nullable = false, columnDefinition = "TEXT")
    private String coordinatesJson;

    @Column(name = "radius_meters")
    private Double radiusMeters;

    @Column(name = "corridor_width_m")
    private Double corridorWidthMeters;

    @Column(name = "assigned_device_ids", columnDefinition = "TEXT")
    private String assignedDeviceIds;

    @Column(name = "assigned_group_ids", columnDefinition = "TEXT")
    private String assignedGroupIds;

    @Column(name = "enter_alert", nullable = false)
    private boolean enterAlert = true;

    @Column(name = "exit_alert", nullable = false)
    private boolean exitAlert = true;

    @Column(name = "active_schedule", length = 256)
    private String activeSchedule;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
