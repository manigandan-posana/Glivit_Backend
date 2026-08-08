package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.entity.DriverScoreDaily;
import com.glivt.ai.entity.TripFeatureSnapshot;
import com.glivt.ai.repository.DriverScoreDailyRepository;
import com.glivt.ai.repository.TripFeatureSnapshotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily driver scoring.
 *
 * <p>Aggregates the trip feature snapshots captured at trip completion, sends the
 * aggregate to the AI service and persists the result with its provenance
 * (source, rule version, model version, calculation time) so the UI never has to
 * guess whether a score came from a model or from rules.
 */
@Service
public class DriverScoringService {

    private static final Logger log = LoggerFactory.getLogger(DriverScoringService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TripFeatureSnapshotRepository tripRepository;
    private final DriverScoreDailyRepository scoreRepository;
    private final PythonAiClient pythonAiClient;
    private final AiGovernanceService governanceService;

    public DriverScoringService(TripFeatureSnapshotRepository tripRepository,
            DriverScoreDailyRepository scoreRepository,
            PythonAiClient pythonAiClient,
            AiGovernanceService governanceService) {
        this.tripRepository = tripRepository;
        this.scoreRepository = scoreRepository;
        this.pythonAiClient = pythonAiClient;
        this.governanceService = governanceService;
    }

    /**
     * Score every driver in one tenant that has trips on {@code scoreDate}.
     *
     * @return how many scores were written
     */
    @Transactional
    public int scoreTenant(Long tenantId, LocalDate scoreDate) {
        Instant from = scoreDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = scoreDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Long> driverIds = tripRepository.findDriverIdsWithTrips(tenantId, from, to);
        int written = 0;
        for (Long driverId : driverIds) {
            try {
                if (scoreDriver(tenantId, driverId, scoreDate, from, to)) {
                    written++;
                }
            } catch (Exception ex) {
                // One driver failing must not abandon the rest of the tenant.
                log.warn("driverScore.failed tenantId={} driverId={} error={}",
                        tenantId, driverId, ex.toString());
            }
        }
        return written;
    }

    private boolean scoreDriver(Long tenantId, Long driverId, LocalDate scoreDate,
            Instant from, Instant to) {
        List<TripFeatureSnapshot> trips =
                tripRepository.findForDriverBetween(tenantId, driverId, from, to);
        if (trips.isEmpty()) {
            return false;
        }

        Aggregate aggregate = Aggregate.from(trips);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("driver_id", driverId);
        payload.put("vehicle_id", aggregate.primaryVehicleId);
        payload.put("period", "DAILY");
        payload.put("score_date", scoreDate.toString());
        payload.put("total_distance_km", aggregate.distanceKm);
        payload.put("total_driving_minutes", aggregate.drivingMinutes);
        payload.put("harsh_accel_count", aggregate.harshAccel);
        payload.put("harsh_brake_count", aggregate.harshBrake);
        payload.put("sharp_turn_count", aggregate.sharpTurn);
        payload.put("speeding_seconds", aggregate.speedingSeconds);
        payload.put("speeding_event_count", aggregate.speedingEvents);
        payload.put("excessive_idle_minutes", aggregate.idleMinutes);
        payload.put("anomalies_count", aggregate.abnormalEvents);
        payload.put("night_driving_minutes", aggregate.nightMinutes);
        payload.put("route_deviation_count", aggregate.routeDeviations);
        payload.put("critical_incident_count", aggregate.criticalIncidents);
        payload.put("high_incident_count", aggregate.highIncidents);
        payload.put("avg_gps_confidence", aggregate.avgGpsConfidence);
        payload.put("trip_count", trips.size());

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/driver/score", payload,
                PythonAiClient.AiCallOptions.of("driver.score", tenantId));

        DriverScoreDaily score = scoreRepository
                .findByTenantIdAndDriverIdAndScoreDateAndScorePeriod(tenantId, driverId, scoreDate, "DAILY")
                .orElseGet(DriverScoreDaily::new);
        score.setTenantId(tenantId);
        score.setDriverId(driverId);
        score.setVehicleId(aggregate.primaryVehicleId);
        score.setScoreDate(scoreDate);
        score.setScorePeriod("DAILY");
        score.setTotalDistanceKm(aggregate.distanceKm);
        score.setTotalDrivingMinutes(aggregate.drivingMinutes);
        score.setHarshAccelCount(aggregate.harshAccel);
        score.setHarshBrakeCount(aggregate.harshBrake);
        score.setSharpTurnCount(aggregate.sharpTurn);
        score.setSpeedingSeconds(aggregate.speedingSeconds);
        score.setExcessiveIdleMinutes(aggregate.idleMinutes);
        score.setAnomaliesCount(aggregate.abnormalEvents);
        score.setCalculatedAt(Instant.now());

        if (result.success()) {
            Map<String, Object> body = result.payload();
            score.setSafetyScore(asDouble(body.get("safety_score"), 0));
            score.setEfficiencyScore(asDouble(body.get("efficiency_score"), 0));
            score.setComplianceScore(asDouble(body.get("compliance_score"), 0));
            score.setOverallScore(asDouble(body.get("overall_score"), 0));
            score.setGrade(asString(body.get("grade")));
            score.setRiskLevel(asString(body.getOrDefault("risk_level", "LOW")));
            score.setBreakdownJson(toJson(body.get("breakdown")));
            score.setReasonsJson(toJson(body.get("reasons")));
            score.setSource("PYTHON_AI");
            score.setRuleVersion(asString(body.get("rule_version")));
            score.setModelVersion(asString(body.get("model_version")));
        } else {
            // The AI service is unavailable. A local rule score is still useful,
            // but it is labelled RULE so nothing claims it came from the model.
            LocalScore local = LocalScore.compute(aggregate);
            score.setSafetyScore(local.safety);
            score.setEfficiencyScore(local.efficiency);
            score.setComplianceScore(local.compliance);
            score.setOverallScore(local.overall);
            score.setGrade(local.grade);
            score.setRiskLevel(local.riskLevel);
            score.setBreakdownJson(toJson(Map.of(
                    "degradedReason", result.errorCode().name(),
                    "harshBrakeCount", aggregate.harshBrake,
                    "harshAccelCount", aggregate.harshAccel,
                    "speedingSeconds", aggregate.speedingSeconds,
                    "idleMinutes", aggregate.idleMinutes)));
            score.setSource("RULE");
            score.setRuleVersion("driver-score-local-1.0");
        }

        scoreRepository.save(score);
        governanceService.record(tenantId, "driver.score", score.getSource(), null,
                score.getModelVersion(), score.getRuleVersion(), result.durationMs(),
                result.success() ? null : result.errorCode().name(), "DRIVER", driverId);
        return true;
    }

    /** Aggregated trip features for one driver-day. */
    private static final class Aggregate {
        private double distanceKm;
        private int drivingMinutes;
        private int idleMinutes;
        private int harshAccel;
        private int harshBrake;
        private int sharpTurn;
        private int speedingSeconds;
        private int speedingEvents;
        private int abnormalEvents;
        private int nightMinutes;
        private int routeDeviations;
        private int criticalIncidents;
        private int highIncidents;
        private double avgGpsConfidence = 1.0;
        private Long primaryVehicleId;

        static Aggregate from(List<TripFeatureSnapshot> trips) {
            Aggregate a = new Aggregate();
            double confidenceSum = 0;
            Map<Long, Integer> vehicleCounts = new LinkedHashMap<>();
            for (TripFeatureSnapshot trip : trips) {
                a.distanceKm += trip.getDistanceKm();
                a.drivingMinutes += trip.getDurationMinutes();
                a.idleMinutes += trip.getIdleDurationMinutes();
                a.harshAccel += trip.getHarshAccelCount();
                a.harshBrake += trip.getHarshBrakeCount();
                a.sharpTurn += trip.getSharpTurnCount();
                a.speedingSeconds += trip.getSpeedingSeconds();
                a.speedingEvents += trip.getSpeedingEventCount();
                a.abnormalEvents += trip.getAbnormalEventCount();
                a.nightMinutes += trip.getNightDrivingMinutes();
                a.routeDeviations += trip.getRouteDeviationCount();
                a.criticalIncidents += trip.getCriticalIncidentCount();
                a.highIncidents += trip.getHighIncidentCount();
                confidenceSum += trip.getAvgGpsConfidence();
                if (trip.getVehicleId() != null) {
                    vehicleCounts.merge(trip.getVehicleId(), 1, Integer::sum);
                }
            }
            a.avgGpsConfidence = trips.isEmpty() ? 1.0 : confidenceSum / trips.size();
            a.primaryVehicleId = vehicleCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            return a;
        }
    }

    /** Local mirror of the AI service's rules, used only when it is unreachable. */
    private record LocalScore(double safety, double efficiency, double compliance, double overall,
            String grade, String riskLevel) {

        static LocalScore compute(Aggregate a) {
            double per100km = Math.max(a.distanceKm / 100.0, 0.1);
            double safety = clamp(100.0 - ((a.harshAccel * 2.0 + a.harshBrake * 3.0
                    + a.sharpTurn * 2.5 + a.criticalIncidents * 8.0 + a.highIncidents * 4.0) / per100km));
            double compliance = clamp(100.0 - (((a.speedingSeconds / 60.0) * 1.5
                    + a.abnormalEvents * 3.0 + a.routeDeviations * 4.0) / per100km));
            double efficiency = clamp(100.0 - ((a.idleMinutes / 60.0) * 8.0
                    + (a.nightMinutes / 60.0) * 2.0));
            double overall = safety * 0.45 + compliance * 0.35 + efficiency * 0.20;
            String grade = overall >= 90 ? "A+" : overall >= 80 ? "A"
                    : overall >= 70 ? "B" : overall >= 60 ? "C" : "D";
            String risk = a.criticalIncidents > 0 || overall < 50 ? "CRITICAL"
                    : overall < 65 ? "HIGH" : overall < 80 ? "MEDIUM" : "LOW";
            return new LocalScore(round1(safety), round1(efficiency), round1(compliance),
                    round1(overall), grade, risk);
        }

        private static double clamp(double value) {
            return Math.max(0.0, Math.min(100.0, value));
        }

        private static double round1(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
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

    private static Optional<Long> asLong(Object value) {
        return value instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
    }
}
