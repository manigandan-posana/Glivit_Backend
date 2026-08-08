package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.entity.MaintenancePrediction;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.MaintenancePredictionRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled predictive-maintenance evaluation.
 *
 * <p>Features come from real telemetry the platform already stores - odometer,
 * engine hours, service history, battery level, harsh-driving exposure, vehicle
 * age - never from placeholders. The persisted prediction records its
 * {@code source}, so a deterministic rule result is never displayed as a
 * trained-model prediction.
 */
@Service
public class MaintenancePredictionService {

    private static final Logger log = LoggerFactory.getLogger(MaintenancePredictionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> HARSH_TYPES =
            List.of("HARSH_BRAKING", "HARSH_ACCELERATION", "SHARP_TURN");

    private final VehicleRepository vehicleRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final AiEventRepository aiEventRepository;
    private final MaintenancePredictionRepository predictionRepository;
    private final PythonAiClient pythonAiClient;
    private final AiGovernanceService governanceService;

    public MaintenancePredictionService(VehicleRepository vehicleRepository,
            DeviceCurrentPositionRepository currentPositionRepository,
            AiEventRepository aiEventRepository,
            MaintenancePredictionRepository predictionRepository,
            PythonAiClient pythonAiClient,
            AiGovernanceService governanceService) {
        this.vehicleRepository = vehicleRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.aiEventRepository = aiEventRepository;
        this.predictionRepository = predictionRepository;
        this.pythonAiClient = pythonAiClient;
        this.governanceService = governanceService;
    }

    /** Evaluate every vehicle in one tenant. Returns how many predictions were written. */
    @Transactional
    public int evaluateTenant(Long tenantId) {
        List<Vehicle> vehicles = vehicleRepository.findByTenantId(tenantId);
        if (vehicles.isEmpty()) {
            return 0;
        }

        Map<Long, DeviceCurrentPosition> positions = new LinkedHashMap<>();
        for (DeviceCurrentPosition position : currentPositionRepository.findByTenantId(tenantId)) {
            if (position.getVehicleId() != null) {
                positions.putIfAbsent(position.getVehicleId(), position);
            }
        }

        Instant since30d = Instant.now().minus(30, ChronoUnit.DAYS);
        int written = 0;
        for (Vehicle vehicle : vehicles) {
            try {
                if (evaluateVehicle(tenantId, vehicle, positions.get(vehicle.getId()), since30d)) {
                    written++;
                }
            } catch (Exception ex) {
                // One vehicle failing must not abandon the rest of the tenant.
                log.warn("maintenance.failed tenantId={} vehicleId={} error={}",
                        tenantId, vehicle.getId(), ex.toString());
            }
        }
        return written;
    }

    private boolean evaluateVehicle(Long tenantId, Vehicle vehicle, DeviceCurrentPosition position,
            Instant since30d) {
        // A vehicle with no odometer or engine hours has nothing to predict from.
        if (vehicle.getOdometer() <= 0 && vehicle.getEngineHours() <= 0) {
            return false;
        }

        long harshEvents = aiEventRepository.countByVehicleAndTypesSince(
                tenantId, vehicle.getId(), HARSH_TYPES, since30d);

        MaintenancePrediction previous = predictionRepository
                .findFirstByTenantIdAndVehicleIdOrderByCreatedAtDesc(tenantId, vehicle.getId())
                .orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("vehicle_id", vehicle.getId());
        payload.put("odometer", vehicle.getOdometer());
        payload.put("engine_hours", vehicle.getEngineHours());
        if (position != null) {
            // Battery is reported as a percentage by most trackers; the AI
            // service converts it when no raw voltage is available.
            payload.put("battery_level_percent", position.getBattery());
            payload.put("battery_voltage", position.getExternalPower());
        }
        payload.put("last_service_odometer",
                previous == null ? 0.0 : previous.getOdometerAtPrediction());
        payload.put("last_service_engine_hours",
                previous == null ? 0.0 : previous.getEngineHoursAtPrediction());
        payload.put("harsh_events_last_30d", harshEvents);
        if (vehicle.getCreatedAt() != null) {
            double ageYears = ChronoUnit.DAYS.between(vehicle.getCreatedAt(), Instant.now()) / 365.0;
            payload.put("vehicle_age_years", Math.max(0.0, ageYears));
        }

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/maintenance/predict",
                payload, new PythonAiClient.AiCallOptions("maintenance.predict", tenantId,
                        vehicle.getId(), 0));
        if (!result.success()) {
            governanceService.record(tenantId, "maintenance.predict", "RULE", null, null, null,
                    result.durationMs(), result.errorCode().name(), "VEHICLE", vehicle.getId());
            return false;
        }

        Map<String, Object> body = result.payload();
        MaintenancePrediction prediction = new MaintenancePrediction();
        prediction.setTenantId(tenantId);
        prediction.setVehicleId(vehicle.getId());
        prediction.setRiskScore(asDouble(body.get("risk_score"), 0));
        prediction.setRiskLevel(asString(body.getOrDefault("risk_level", "LOW")));
        Integer daysRemaining = body.get("predicted_days_remaining") instanceof Number number
                ? number.intValue() : null;
        prediction.setPredictedDaysRemaining(daysRemaining);
        if (daysRemaining != null) {
            prediction.setPredictedFailureDate(LocalDate.now(ZoneOffset.UTC).plusDays(daysRemaining));
        }
        prediction.setPredictedComponent(asString(body.get("predicted_component")));
        prediction.setComponentsJson(toJson(body.get("components")));
        prediction.setRemainingKm(body.get("remaining_distance_km") instanceof Number number
                ? number.doubleValue() : null);
        prediction.setOdometerAtPrediction(vehicle.getOdometer());
        prediction.setEngineHoursAtPrediction(vehicle.getEngineHours());
        prediction.setBatteryHealth(asDouble(body.get("battery_health_percent"), 100));
        prediction.setDrivingStressFactor(asDouble(body.get("driving_stress_factor"), 1));
        prediction.setRecommendedAction(joinActions(body.get("recommended_actions")));
        prediction.setReasoning(asString(body.get("reasoning")));
        prediction.setConfidence(asDouble(body.get("confidence"), 0.6));
        // MODEL only when the AI service says a trained model contributed.
        prediction.setSource(asString(body.getOrDefault("source", "RULE")));
        prediction.setModelVersion(asString(body.get("model_version")));
        prediction.setRuleVersion(asString(body.get("rule_version")));
        prediction.setEvaluatedAt(Instant.now());
        prediction.setStatus("PENDING");
        predictionRepository.save(prediction);

        governanceService.record(tenantId, "maintenance.predict", prediction.getSource(), null,
                prediction.getModelVersion(), prediction.getRuleVersion(), result.durationMs(),
                null, "VEHICLE", vehicle.getId());
        return true;
    }

    private static String joinActions(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Object action : list) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(action);
        }
        return builder.toString();
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
