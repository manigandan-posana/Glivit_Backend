package com.glivt.ai.service;

import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.entity.GeofenceSuggestion;
import com.glivt.ai.repository.GeofenceSuggestionRepository;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates geofence suggestions from a tenant's real stop points.
 *
 * <p>Stops are derived from stored positions: a run of consecutive low-speed
 * points at the same place with the ignition off, lasting longer than
 * {@link #MIN_STOP_MINUTES}. Only that tenant's coordinates are ever sent to the
 * clustering endpoint.
 */
@Service
public class GeofenceSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(GeofenceSuggestionService.class);

    private static final double STOP_SPEED_KPH = 3.0;
    private static final int MIN_STOP_MINUTES = 10;
    private static final double SAME_PLACE_METERS = 80.0;
    private static final int MAX_POSITIONS = 20_000;
    private static final int MAX_SUGGESTIONS = 20;

    private final PositionRepository positionRepository;
    private final GeofenceSuggestionRepository suggestionRepository;
    private final PythonAiClient pythonAiClient;
    private final AiGovernanceService governanceService;

    public GeofenceSuggestionService(PositionRepository positionRepository,
            GeofenceSuggestionRepository suggestionRepository,
            PythonAiClient pythonAiClient,
            AiGovernanceService governanceService) {
        this.positionRepository = positionRepository;
        this.suggestionRepository = suggestionRepository;
        this.pythonAiClient = pythonAiClient;
        this.governanceService = governanceService;
    }

    /** @return number of suggestions written for this tenant */
    @Transactional
    public int generateForTenant(Long tenantId, int lookbackDays) {
        Instant since = Instant.now().minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
        List<Position> positions = positionRepository.findStopCandidates(tenantId, since,
                STOP_SPEED_KPH, org.springframework.data.domain.PageRequest.of(0, MAX_POSITIONS));
        if (positions.size() < 3) {
            return 0;
        }

        List<Map<String, Object>> stops = deriveStops(positions);
        if (stops.size() < 3) {
            return 0;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("stop_points", stops);
        payload.put("eps_meters", 250.0);
        payload.put("min_samples", 3);

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/geofence/cluster",
                payload, PythonAiClient.AiCallOptions.of("geofence.cluster", tenantId));
        if (!result.success()) {
            governanceService.record(tenantId, "geofence.cluster", "RULE", null, null, null,
                    result.durationMs(), result.errorCode().name(), "TENANT", tenantId);
            return 0;
        }

        Object rawSuggestions = result.payload().get("suggestions");
        if (!(rawSuggestions instanceof List<?> list)) {
            return 0;
        }

        // Replace the previous PENDING batch so suggestions do not accumulate;
        // reviewed (approved/dismissed) ones are left untouched.
        suggestionRepository.deleteAll(
                suggestionRepository.findByTenantIdAndStatus(tenantId, "PENDING"));

        int written = 0;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw) || written >= MAX_SUGGESTIONS) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) raw;

            GeofenceSuggestion suggestion = new GeofenceSuggestion();
            suggestion.setTenantId(tenantId);
            suggestion.setSuggestedName(asString(item.getOrDefault("suggested_name",
                    "Frequent Stop " + (written + 1))));
            suggestion.setCenterLatitude(asDouble(item.get("center_latitude"), 0));
            suggestion.setCenterLongitude(asDouble(item.get("center_longitude"), 0));
            suggestion.setSuggestedRadiusMeters(asDouble(item.get("suggested_radius_meters"), 150));
            suggestion.setClusterPointCount((int) asDouble(item.get("cluster_point_count"), 0));
            suggestion.setVisitCount((int) asDouble(item.get("visit_count"), 0));
            suggestion.setAverageStopMinutes(asDouble(item.get("average_stop_minutes"), 0));
            suggestion.setFirstVisitAt(parseInstant(item.get("first_visit_at")));
            suggestion.setLastVisitAt(parseInstant(item.get("last_visit_at")));
            suggestion.setDistinctVehicleCount((int) asDouble(item.get("distinct_vehicle_count"), 0));
            suggestion.setConfidence(asDouble(item.get("confidence"), 0.5));
            suggestion.setReasoning(asString(item.get("reasoning")));
            suggestion.setStatus("PENDING");
            suggestionRepository.save(suggestion);
            written++;
        }

        governanceService.record(tenantId, "geofence.cluster", "PYTHON_AI", null, null,
                asString(result.payload().get("rule_version")), result.durationMs(), null,
                "TENANT", tenantId);
        return written;
    }

    /**
     * Collapses consecutive low-speed positions at the same place into stops.
     * Positions arrive ordered by device time.
     */
    private List<Map<String, Object>> deriveStops(List<Position> positions) {
        List<Map<String, Object>> stops = new ArrayList<>();

        int index = 0;
        while (index < positions.size()) {
            Position anchor = positions.get(index);
            int end = index;
            while (end + 1 < positions.size()) {
                Position next = positions.get(end + 1);
                if (!anchor.getDeviceId().equals(next.getDeviceId())) {
                    break;
                }
                double moved = GeoMath.haversineMeters(anchor.getLatitude(), anchor.getLongitude(),
                        next.getLatitude(), next.getLongitude());
                if (moved > SAME_PLACE_METERS) {
                    break;
                }
                end++;
            }

            Position last = positions.get(end);
            long minutes = anchor.getDeviceTime() != null && last.getDeviceTime() != null
                    ? ChronoUnit.MINUTES.between(anchor.getDeviceTime(), last.getDeviceTime())
                    : 0;
            if (minutes >= MIN_STOP_MINUTES) {
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("latitude", anchor.getLatitude());
                stop.put("longitude", anchor.getLongitude());
                stop.put("duration_minutes", (double) minutes);
                stop.put("occurred_at", anchor.getDeviceTime() == null
                        ? null : anchor.getDeviceTime().toString());
                stop.put("vehicle_id", anchor.getVehicleId());
                stops.add(stop);
            }
            index = end + 1;
        }
        return stops;
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            try {
                return java.time.OffsetDateTime.parse(String.valueOf(value)).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
