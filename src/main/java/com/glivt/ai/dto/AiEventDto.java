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
}
