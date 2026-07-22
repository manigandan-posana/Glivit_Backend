package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.dto.AiEventDto;
import com.glivt.ai.dto.GpsFeatures;
import com.glivt.position.Position;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.glivt.ai.config.AiAsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiAsyncEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AiAsyncEvaluatorService.class);

    private final PythonAiClient pythonAiClient;
    private final OllamaAiClient ollamaAiClient;
    private final AiEventRepository aiEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AiAlertBroadcaster broadcaster;
    // Boot 4 provides a Jackson 3 (tools.jackson) mapper bean, not this
    // com.fasterxml type, so construct our own for evidence serialisation.
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public AiAsyncEvaluatorService(
            PythonAiClient pythonAiClient,
            OllamaAiClient ollamaAiClient,
            AiEventRepository aiEventRepository,
            VehicleRepository vehicleRepository,
            AiAlertBroadcaster broadcaster) {
        this.pythonAiClient = pythonAiClient;
        this.ollamaAiClient = ollamaAiClient;
        this.aiEventRepository = aiEventRepository;
        this.vehicleRepository = vehicleRepository;
        this.broadcaster = broadcaster;
    }

    @Async(AiAsyncConfig.AI_EXECUTOR)
    public void evaluatePositionAsync(Position position, GpsFeatures features, double speedLimitKph) {
        if (position == null || !features.coordinateValid()) {
            return;
        }

        try {
            Map<String, Object> reqPayload = new HashMap<>();
            reqPayload.put("tenant_id", position.getTenantId());
            reqPayload.put("vehicle_id", position.getVehicleId() != null ? position.getVehicleId() : 0L);
            reqPayload.put("device_id", position.getDeviceId());
            reqPayload.put("speed_kph", position.getSpeed());
            reqPayload.put("speed_limit_kph", speedLimitKph);
            reqPayload.put("calculated_speed_kph", features.calculatedSpeedKph());
            reqPayload.put("acceleration_mps2", features.accelerationMps2());
            reqPayload.put("heading_change_degrees", features.headingChangeDegrees());
            reqPayload.put("location_jump_meters", features.distanceFromPreviousMeters());
            reqPayload.put("time_gap_seconds", Math.max(features.timeFromPreviousSeconds(), 0.5));
            reqPayload.put("gps_accuracy_meters", position.getAccuracy() != null ? position.getAccuracy() : 5.0);
            reqPayload.put("distance_from_route_meters", 0.0);
            reqPayload.put("stationary_duration_seconds", features.stationaryDurationSeconds());
            reqPayload.put("latitude", position.getLatitude());
            reqPayload.put("longitude", position.getLongitude());
            reqPayload.put("ignition_on", Boolean.TRUE.equals(position.getIgnition()));
            reqPayload.put("recorded_at", position.getDeviceTime() != null ? position.getDeviceTime().toString() : Instant.now().toString());

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = pythonAiClient.post("/v1/anomaly/score", reqPayload, Map.class);

            if (resp == null) {
                return;
            }

            Double score = ((Number) resp.getOrDefault("anomaly_score", 0.0)).doubleValue();
            String severity = (String) resp.getOrDefault("severity", "LOW");

            @SuppressWarnings("unchecked")
            List<String> eventTypes = (List<String>) resp.get("event_types");

            if (score != null && score >= 0.25 && eventTypes != null && !eventTypes.isEmpty()) {
                String eventType = eventTypes.get(0);

                String explanation = ollamaAiClient.generateExplanation(
                        "You are an AI Fleet Safety Assistant. Provide a 1-sentence concise alert explanation.",
                        "Event: " + eventType + ", Speed: " + position.getSpeed() + " km/h, Score: " + score,
                        "AI Anomaly Detected: " + eventType + " with score " + score
                );

                AiEvent event = new AiEvent();
                event.setTenantId(position.getTenantId());
                event.setVehicleId(position.getVehicleId());
                event.setDeviceId(position.getDeviceId());
                event.setEventType(eventType);
                event.setSeverity(severity);
                event.setScore(score);
                event.setLatitude(position.getLatitude());
                event.setLongitude(position.getLongitude());
                event.setSpeed(position.getSpeed());
                event.setExplanation(explanation);

                if (resp.get("evidence") != null) {
                    event.setEvidenceJson(objectMapper.writeValueAsString(resp.get("evidence")));
                }

                AiEvent saved = aiEventRepository.save(event);

                String vehicleName = "Vehicle #" + (position.getVehicleId() != null ? position.getVehicleId() : "N/A");
                if (position.getVehicleId() != null) {
                    Vehicle v = vehicleRepository.findById(position.getVehicleId()).orElse(null);
                    if (v != null) {
                        vehicleName = v.getName();
                    }
                }

                AiEventDto dto = AiEventDto.builder()
                        .id(saved.getId())
                        .tenantId(saved.getTenantId())
                        .vehicleId(saved.getVehicleId())
                        .vehicleName(vehicleName)
                        .deviceId(saved.getDeviceId())
                        .eventType(saved.getEventType())
                        .severity(saved.getSeverity())
                        .score(saved.getScore())
                        .latitude(saved.getLatitude())
                        .longitude(saved.getLongitude())
                        .speed(saved.getSpeed())
                        .explanation(saved.getExplanation())
                        .createdAt(saved.getCreatedAt())
                        .build();

                broadcaster.broadcast(position.getTenantId(), dto);
            }
        } catch (Exception ex) {
            log.error("Error in async AI position evaluation: {}", ex.getMessage());
        }
    }
}
