package com.glivt.ai.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEventDto {
    private Long id;
    private Long tenantId;
    private Long vehicleId;
    private String vehicleName;
    private Long deviceId;
    private Long driverId;
    private String driverName;
    private String eventType;
    private String severity;
    private double score;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private String deviationPathJson;
    private String reentryPointJson;
    private String explanation;
    private String evidenceJson;
    private boolean acknowledged;
    private Long acknowledgedBy;
    private Instant acknowledgedAt;
    private Instant createdAt;

    // Incident view: repeated detections fold into one record rather than
    // producing a row per GPS packet.
    /** OPEN | ACKNOWLEDGED | RESOLVED */
    private String status;
    private int occurrenceCount;
    private Instant firstObservedAt;
    private Instant lastObservedAt;
    /** Other anomaly types detected alongside this one. */
    private java.util.List<String> relatedEventTypes;

    // Resolved context, so the UI never has to guess.
    private Long routeId;
    private Double distanceFromRouteMeters;
    private Double speedLimitKph;
    /** ROUTE_RULE | GEOFENCE_RULE | ROAD_METADATA | TENANT_POLICY | VEHICLE_TYPE_DEFAULT */
    private String speedLimitSource;
    /** RULE | ML_ASSISTED - never claims a model produced a rule result. */
    private String source;
}
