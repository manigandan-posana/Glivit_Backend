package com.glivt.event;

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
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(nullable = false, length = 16)
    private String severity = "INFO";

    private Double latitude;
    private Double longitude;
    private Double speed;

    @Column(length = 512)
    private String address;

    @Column(name = "device_time")
    private Instant deviceTime;

    @Column(name = "server_time", nullable = false)
    private Instant serverTime;

    @Column(nullable = false)
    private boolean acknowledged = false;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (serverTime == null) {
            serverTime = now;
        }
        createdAt = now;
    }
}
