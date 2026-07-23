package com.glivt.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "trip_feature_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class TripFeatureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "distance_km", nullable = false)
    private double distanceKm = 0.0;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 0;

    @Column(name = "idle_duration_minutes", nullable = false)
    private int idleDurationMinutes = 0;

    @Column(name = "avg_speed_kph", nullable = false)
    private double avgSpeedKph = 0.0;

    @Column(name = "max_speed_kph", nullable = false)
    private double maxSpeedKph = 0.0;

    @Column(name = "stop_count", nullable = false)
    private int stopCount = 0;

    @Column(name = "abnormal_event_count", nullable = false)
    private int abnormalEventCount = 0;

    @Column(name = "harsh_event_count", nullable = false)
    private int harshEventCount = 0;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes = 0;

    @Column(name = "stops_json", columnDefinition = "TEXT")
    private String stopsJson;

    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
