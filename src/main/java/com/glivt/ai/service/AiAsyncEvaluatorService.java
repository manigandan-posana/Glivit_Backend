package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.dto.AiEventDto;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.service.DriverAssignmentResolver.ResolvedDriver;
import com.glivt.ai.service.RouteDeviationService.RouteDeviation;
import com.glivt.ai.service.SpeedLimitResolver.ResolvedSpeedLimit;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scores one committed GPS position for anomalies and turns the result into
 * incidents.
 *
 * <p>Everything safety-critical is resolved here in Java, from tenant-scoped
 * data, before the AI service is asked for a score:
 * <ul>
 *   <li>the assigned route and the real distance from it (no hard-coded 0.0);</li>
 *   <li>the speed limit, server-side, never the device's claim;</li>
 *   <li>the driver on duty at the packet's timestamp;</li>
 *   <li>continuous stationary time from persistent motion state.</li>
 * </ul>
 *
 * <p>Anomalies come back as an ordered list of structured events. Each is
 * recorded through {@link AiIncidentService}, which folds repeats into a single
 * incident so continuous speeding cannot flood the alert list.
 */
@Service
public class AiAsyncEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AiAsyncEvaluatorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Below this score a detection is noise and is not persisted. */
    private static final double MIN_PERSIST_SCORE = 0.25;
    /** How many events from one packet may become separate incidents. */
    private static final int MAX_EVENTS_PER_PACKET = 5;

    private final PythonAiClient pythonAiClient;
    private final VehicleRepository vehicleRepository;
    private final RouteDeviationService routeDeviationService;
    private final SpeedLimitResolver speedLimitResolver;
    private final DriverAssignmentResolver driverAssignmentResolver;
    private final AiIncidentService incidentService;
    private final AiAlertBroadcaster broadcaster;
    private final AiGovernanceService governanceService;

    public AiAsyncEvaluatorService(PythonAiClient pythonAiClient,
            VehicleRepository vehicleRepository,
            RouteDeviationService routeDeviationService,
            SpeedLimitResolver speedLimitResolver,
            DriverAssignmentResolver driverAssignmentResolver,
            AiIncidentService incidentService,
            AiAlertBroadcaster broadcaster,
            AiGovernanceService governanceService) {
        this.pythonAiClient = pythonAiClient;
        this.vehicleRepository = vehicleRepository;
        this.routeDeviationService = routeDeviationService;
        this.speedLimitResolver = speedLimitResolver;
        this.driverAssignmentResolver = driverAssignmentResolver;
        this.incidentService = incidentService;
        this.broadcaster = broadcaster;
        this.governanceService = governanceService;
    }

    /**
     * Evaluate one position. Called by the outbox worker, never by the ingestion
     * request thread.
     *
     * @return the number of incidents created or updated
     */
    public int evaluate(AiEvaluationPayload payload) {
        if (payload == null || payload.tenantId() == null) {
            return 0;
        }

        Vehicle vehicle = payload.vehicleId() == null
                ? null
                : vehicleRepository.findByIdAndTenantId(payload.vehicleId(), payload.tenantId())
                        .orElse(null);

        // 1. Route corridor - tenant-scoped, real geometry.
        Optional<RouteDeviation> deviation = routeDeviationService.resolve(
                payload.tenantId(), payload.vehicleId(), payload.latitude(), payload.longitude(),
                payload.recordedAt());

        // 2. Speed limit - resolved server-side, never taken from the device.
        ResolvedSpeedLimit speedLimit = speedLimitResolver.resolve(
                payload.tenantId(), vehicle, payload.latitude(), payload.longitude(),
                deviation.map(RouteDeviation::routeSpeedLimitKph).orElse(null),
                deviation.map(RouteDeviation::routeName).orElse(null),
                null);

        // 3. Driver on duty at the packet timestamp.
        Optional<ResolvedDriver> driver = driverAssignmentResolver.resolve(
                payload.tenantId(), payload.vehicleId(), payload.recordedAt());

        Map<String, Object> request = buildRequest(payload, deviation, speedLimit, driver);

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap(
                "/v1/anomaly/score", request,
                new PythonAiClient.AiCallOptions("anomaly.score", payload.tenantId(),
                        payload.vehicleId(), 0));

        if (!result.success()) {
            // The AI service is unavailable. GPS data is already stored and the
            // live map is already updated; only the scoring is skipped.
            log.debug("ai.anomaly.skipped tenantId={} vehicleId={} errorCode={}",
                    payload.tenantId(), payload.vehicleId(), result.errorCode());
            governanceService.record(payload.tenantId(), "anomaly.score", "RULE", null, null,
                    null, result.durationMs(), result.errorCode().name(),
                    "POSITION", payload.positionId());
            return 0;
        }

        return persistEvents(payload, result, deviation, speedLimit, driver);
    }

    private Map<String, Object> buildRequest(AiEvaluationPayload payload,
            Optional<RouteDeviation> deviation, ResolvedSpeedLimit speedLimit,
            Optional<ResolvedDriver> driver) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenant_id", payload.tenantId());
        request.put("vehicle_id", payload.vehicleId() != null ? payload.vehicleId() : 0L);
        request.put("device_id", payload.deviceId());
        driver.ifPresent(d -> {
            request.put("driver_id", d.driverId());
            request.put("assignment_id", d.assignmentId());
        });

        request.put("speed_kph", Math.max(0.0, payload.speedKph()));
        request.put("calculated_speed_kph", Math.max(0.0, payload.calculatedSpeedKph()));
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("limit_kph", speedLimit.limitKph());
        limit.put("source", speedLimit.source());
        limit.put("source_reference", speedLimit.sourceReference());
        request.put("speed_limit", limit);
        // Legacy flat field kept in sync for older AI-service builds.
        request.put("speed_limit_kph", speedLimit.limitKph());

        request.put("acceleration_mps2", payload.accelerationMps2());
        request.put("heading_change_degrees", payload.headingChangeDegrees());
        request.put("location_jump_meters", Math.max(0.0, payload.locationJumpMeters()));
        // Guaranteed positive: out-of-order packets never reach this point, so a
        // negative gap is never coerced into a fake 0.5 seconds.
        request.put("time_gap_seconds", Math.max(0.001, payload.timeGapSeconds()));
        request.put("gps_accuracy_meters",
                payload.gpsAccuracyMeters() != null ? payload.gpsAccuracyMeters() : 5.0);
        request.put("gps_confidence", payload.gpsConfidence());
        request.put("stationary_duration_seconds", Math.max(0.0, payload.stationarySeconds()));

        deviation.ifPresent(d -> {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("route_id", d.routeId());
            route.put("route_name", d.routeName());
            route.put("distance_from_route_meters", d.distanceMeters());
            route.put("allowed_deviation_meters", d.allowedDeviation());
            route.put("inside_corridor", d.insideCorridor());
            route.put("nearest_route_latitude", d.nearestLatitude());
            route.put("nearest_route_longitude", d.nearestLongitude());
            route.put("reentry_latitude", d.reentryLatitude());
            route.put("reentry_longitude", d.reentryLongitude());
            request.put("route", route);
        });

        request.put("latitude", payload.latitude());
        request.put("longitude", payload.longitude());
        request.put("ignition_on", Boolean.TRUE.equals(payload.ignitionOn()));
        if (payload.recordedAt() != null) {
            request.put("recorded_at", payload.recordedAt().toString());
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private int persistEvents(AiEvaluationPayload payload, AiResult<Map<String, Object>> result,
            Optional<RouteDeviation> deviation, ResolvedSpeedLimit speedLimit,
            Optional<ResolvedDriver> driver) {

        Map<String, Object> body = result.payload();
        Object rawEvents = body.get("events");
        if (!(rawEvents instanceof List<?> eventList) || eventList.isEmpty()) {
            return 0;
        }

        String deviationPathJson = toJson(body.get("deviation_path"));
        String reentryPointJson = toJson(body.get("reentry_point"));
        String ruleVersion = asString(body.get("rule_version"));
        String modelVersion = asString(body.get("model_version"));
        String source = asString(body.get("source"));

        // Every type detected on this packet, so a stored incident can reference
        // the others it occurred alongside.
        Set<String> allTypes = new LinkedHashSet<>();
        for (Object entry : eventList) {
            if (entry instanceof Map<?, ?> map && map.get("type") != null) {
                allTypes.add(String.valueOf(map.get("type")));
            }
        }

        int persisted = 0;
        int considered = 0;
        for (Object entry : eventList) {
            if (!(entry instanceof Map<?, ?> rawMap) || considered++ >= MAX_EVENTS_PER_PACKET) {
                continue;
            }
            Map<String, Object> event = (Map<String, Object>) rawMap;

            double score = asDouble(event.get("score"), 0.0);
            String eventType = asString(event.get("type"));
            if (score < MIN_PERSIST_SCORE || eventType == null || eventType.isBlank()) {
                continue;
            }

            AiEvent candidate = new AiEvent();
            candidate.setTenantId(payload.tenantId());
            candidate.setVehicleId(payload.vehicleId());
            candidate.setDeviceId(payload.deviceId());
            driver.ifPresent(d -> {
                candidate.setDriverId(d.driverId());
                candidate.setAssignmentId(d.assignmentId());
            });
            candidate.setEventType(eventType);
            candidate.setSeverity(asString(event.getOrDefault("severity", "MEDIUM")));
            candidate.setScore(score);
            candidate.setLatitude(payload.latitude());
            candidate.setLongitude(payload.longitude());
            candidate.setSpeed(payload.speedKph());
            // The explanation is the deterministic one produced alongside the
            // score - no LLM call is made per GPS packet.
            candidate.setExplanation(asString(event.get("explanation")));
            candidate.setEvidenceJson(toJson(event.get("evidence")));
            candidate.setCreatedAt(payload.recordedAt());

            candidate.setSpeedLimitKph(speedLimit.limitKph());
            candidate.setSpeedLimitSource(speedLimit.source());
            deviation.ifPresent(d -> {
                candidate.setRouteId(d.routeId());
                candidate.setDistanceFromRouteMeters(d.distanceMeters());
            });
            if ("ROUTE_DEVIATION".equals(eventType)) {
                candidate.setDeviationPathJson(deviationPathJson);
                candidate.setReentryPointJson(reentryPointJson);
            }

            candidate.setSource(source == null ? "RULE" : source);
            candidate.setRuleVersion(ruleVersion);
            candidate.setModelVersion(modelVersion);
            candidate.setProcessingMs(result.durationMs());

            Set<String> related = new LinkedHashSet<>(allTypes);
            related.remove(eventType);

            Optional<AiIncidentService.IncidentOutcome> outcome =
                    incidentService.record(candidate, related);
            if (outcome.isEmpty()) {
                continue;
            }
            persisted++;

            AiIncidentService.IncidentOutcome incident = outcome.get();
            // Only a new incident or a real escalation is pushed to operators;
            // repeat observations update the record silently.
            if (incident.created() || incident.escalated()) {
                broadcaster.broadcast(payload.tenantId(), toDto(incident.event()));
            }
        }

        governanceService.record(payload.tenantId(), "anomaly.score",
                source == null ? "RULE" : source, null, modelVersion, ruleVersion,
                result.durationMs(), null, "POSITION", payload.positionId());

        if (persisted > 0) {
            log.debug("ai.anomaly.persisted tenantId={} vehicleId={} incidents={} types={}",
                    payload.tenantId(), payload.vehicleId(), persisted, allTypes);
        }
        return persisted;
    }

    /** Maps a stored incident to the DTO pushed over SSE and returned by the API. */
    public AiEventDto toDto(AiEvent event) {
        String vehicleName = event.getVehicleId() == null
                ? "Unassigned"
                : vehicleRepository.findByIdAndTenantId(event.getVehicleId(), event.getTenantId())
                        .map(Vehicle::getName)
                        .orElse("Vehicle #" + event.getVehicleId());

        return AiEventDto.builder()
                .id(event.getId())
                .tenantId(event.getTenantId())
                .vehicleId(event.getVehicleId())
                .vehicleName(vehicleName)
                .deviceId(event.getDeviceId())
                .driverId(event.getDriverId())
                .eventType(event.getEventType())
                .severity(event.getSeverity())
                .score(event.getScore())
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .speed(event.getSpeed())
                .deviationPathJson(event.getDeviationPathJson())
                .reentryPointJson(event.getReentryPointJson())
                .explanation(event.getExplanation())
                .evidenceJson(event.getEvidenceJson())
                .acknowledged(event.isAcknowledged())
                .acknowledgedBy(event.getAcknowledgedBy())
                .acknowledgedAt(event.getAcknowledgedAt())
                .createdAt(event.getCreatedAt())
                .status(event.getStatus())
                .occurrenceCount(event.getOccurrenceCount())
                .firstObservedAt(event.getFirstObservedAt())
                .lastObservedAt(event.getLastObservedAt())
                .relatedEventTypes(splitTypes(event.getRelatedEventTypes()))
                .routeId(event.getRouteId())
                .distanceFromRouteMeters(event.getDistanceFromRouteMeters())
                .speedLimitKph(event.getSpeedLimitKph())
                .speedLimitSource(event.getSpeedLimitSource())
                .source(event.getSource())
                .build();
    }

    static List<String> splitTypes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
