package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.dto.AiDashboardSummaryDto;
import com.glivt.ai.dto.AiEventDto;
import com.glivt.ai.dto.DispatchRecommendRequestDto;
import com.glivt.ai.dto.DispatchRecommendResponseDto;
import com.glivt.ai.dto.DriverScoreDto;
import com.glivt.ai.dto.EtaRequestDto;
import com.glivt.ai.dto.EtaResponseDto;
import com.glivt.ai.dto.FeedbackRequestDto;
import com.glivt.ai.dto.GeofenceSuggestionDto;
import com.glivt.ai.dto.MaintenancePredictionDto;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.entity.AiFeedback;
import com.glivt.ai.entity.DispatchRecommendation;
import com.glivt.ai.entity.DriverScoreDaily;
import com.glivt.ai.entity.GeofenceSuggestion;
import com.glivt.ai.entity.MaintenancePrediction;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.AiFeedbackRepository;
import com.glivt.ai.repository.DispatchRecommendationRepository;
import com.glivt.ai.repository.DriverScoreDailyRepository;
import com.glivt.ai.repository.GeofenceSuggestionRepository;
import com.glivt.ai.repository.MaintenancePredictionRepository;
import com.glivt.ai.security.FleetAccessPolicy;
import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.driver.Driver;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/aggregation service backing the AI command centre and per-vehicle AI
 * views. Every method resolves data strictly within the caller's tenant and
 * runs deterministic, evidence-based logic. Optional Python ML is attempted but
 * always degrades to an explainable rule-based result (source = RULE) so AI
 * outages never break the API. Synchronous request paths never wait on Ollama.
 */
@Service
public class AiFleetService {

    private static final Logger log = LoggerFactory.getLogger(AiFleetService.class);
    private static final Set<DeviceState> ACTIVE_STATES =
            Set.of(DeviceState.RUNNING, DeviceState.STOPPED, DeviceState.IDLE);
    private static final Set<String> HIGH_RISK_LEVELS = Set.of("HIGH", "CRITICAL");
    private static final double RISKY_DRIVER_THRESHOLD = 60.0;

    private final AiEventRepository aiEventRepository;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final DriverScoreDailyRepository driverScoreRepository;
    private final GeofenceSuggestionRepository geofenceSuggestionRepository;
    private final MaintenancePredictionRepository maintenanceRepository;
    private final DispatchRecommendationRepository dispatchRepository;
    private final VehicleRepository vehicleRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final FleetAccessPolicy accessPolicy;
    private final AuditService auditService;
    private final PythonAiClient pythonAiClient;
    // Boot 4 auto-configures a Jackson 3 (tools.jackson) mapper, not this
    // com.fasterxml type, so construct our own for internal JSON serialisation.
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public AiFleetService(AiEventRepository aiEventRepository,
                          AiFeedbackRepository aiFeedbackRepository,
                          DriverScoreDailyRepository driverScoreRepository,
                          GeofenceSuggestionRepository geofenceSuggestionRepository,
                          MaintenancePredictionRepository maintenanceRepository,
                          DispatchRecommendationRepository dispatchRepository,
                          VehicleRepository vehicleRepository,
                          DeviceCurrentPositionRepository currentPositionRepository,
                          FleetAccessPolicy accessPolicy,
                          AuditService auditService,
                          PythonAiClient pythonAiClient) {
        this.aiEventRepository = aiEventRepository;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.driverScoreRepository = driverScoreRepository;
        this.geofenceSuggestionRepository = geofenceSuggestionRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.dispatchRepository = dispatchRepository;
        this.vehicleRepository = vehicleRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.pythonAiClient = pythonAiClient;
    }

    // ---------------------------------------------------------------------
    // Command centre dashboard
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AiDashboardSummaryDto dashboard(Long tenantId) {
        long activeVehicles = currentPositionRepository.countByStateForTenant(tenantId).stream()
                .filter(sc -> ACTIVE_STATES.contains(sc.getState()))
                .mapToLong(DeviceCurrentPositionRepository.StateCount::getTotal)
                .sum();

        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        long unackAlerts = aiEventRepository.countByTenantIdAndAcknowledgedFalse(tenantId);
        long criticalRisk = aiEventRepository
                .countByTenantIdAndSeverityAndCreatedAtAfter(tenantId, "CRITICAL", since24h);
        long highMaintenance = maintenanceRepository
                .countByTenantIdAndRiskLevelInAndStatus(tenantId, List.copyOf(HIGH_RISK_LEVELS), "PENDING");
        long routeDeviations = aiEventRepository
                .findFiltered(tenantId, null, null, "ROUTE_DEVIATION", PageRequest.of(0, 1))
                .getTotalElements();

        long riskyDrivers = driverScoreRepository
                .findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
                        tenantId, LocalDate.now(ZoneOffset.UTC), "DAILY")
                .stream()
                .filter(s -> s.getOverallScore() < RISKY_DRIVER_THRESHOLD)
                .count();

        List<AiEvent> recent = aiEventRepository.findTop10ByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<Long, String> vehicleNames = vehicleNamesFor(tenantId, recent.stream()
                .map(AiEvent::getVehicleId).toList());
        List<AiEventDto> recentDtos = recent.stream()
                .map(e -> toDto(e, vehicleNames))
                .collect(Collectors.toList());

        double fleetHealth = fleetHealthScore(unackAlerts, criticalRisk, highMaintenance,
                riskyDrivers, routeDeviations);

        String summary = String.format(
                "Fleet health %.0f/100. %d active vehicle(s), %d open AI alert(s), "
                        + "%d critical event(s) in 24h, %d high maintenance risk(s), "
                        + "%d driver(s) needing coaching, %d active route deviation(s).",
                fleetHealth, activeVehicles, unackAlerts, criticalRisk, highMaintenance,
                riskyDrivers, routeDeviations);

        return AiDashboardSummaryDto.builder()
                .fleetHealthScore(round1(fleetHealth))
                .totalActiveVehicles(activeVehicles)
                .unacknowledgedAiAlerts(unackAlerts)
                .criticalRiskVehicles(criticalRisk)
                .highRiskMaintenanceCount(highMaintenance)
                .riskyDriversCount(riskyDrivers)
                .activeRouteDeviationsCount(routeDeviations)
                .recentCriticalEvents(recentDtos)
                .executiveAiSummary(summary)
                .build();
    }

    private static double fleetHealthScore(long unackAlerts, long criticalRisk, long highMaintenance,
                                           long riskyDrivers, long routeDeviations) {
        double score = 100.0;
        score -= Math.min(30.0, unackAlerts * 2.0);
        score -= Math.min(25.0, criticalRisk * 5.0);
        score -= Math.min(20.0, highMaintenance * 4.0);
        score -= Math.min(15.0, riskyDrivers * 3.0);
        score -= Math.min(10.0, routeDeviations * 2.0);
        return Math.max(0.0, score);
    }

    // ---------------------------------------------------------------------
    // AI events
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AiEventDto> listEvents(Long tenantId, Long vehicleId, String severity,
                                               String eventType, Pageable pageable) {
        if (vehicleId != null) {
            accessPolicy.requireVehicle(tenantId, vehicleId);
        }
        Page<AiEvent> page = aiEventRepository.findFiltered(tenantId, vehicleId,
                blankToNull(severity), blankToNull(eventType), pageable);
        Map<Long, String> vehicleNames = vehicleNamesFor(tenantId, page.getContent().stream()
                .map(AiEvent::getVehicleId).toList());
        return PageResponse.from(page, e -> toDto(e, vehicleNames));
    }

    @Transactional
    public AiEventDto acknowledge(Long tenantId, Long userId, String username, Long eventId) {
        AiEvent event = aiEventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AI event not found"));
        if (!event.isAcknowledged()) {
            event.setAcknowledged(true);
            event.setAcknowledgedBy(userId);
            event.setAcknowledgedAt(Instant.now());
            event = aiEventRepository.save(event);
        }
        auditService.record(tenantId, userId, username, "ACKNOWLEDGE_AI_EVENT", "AI_EVENT",
                String.valueOf(eventId), "SUCCESS", "eventType=" + event.getEventType());
        return toDto(event, vehicleNamesFor(tenantId, List.of()));
    }

    @Transactional
    public void submitFeedback(Long tenantId, Long userId, String username, FeedbackRequestDto req) {
        if (req.getAiEventId() != null) {
            // Prevent cross-tenant feedback (IDOR) by validating ownership first.
            aiEventRepository.findByIdAndTenantId(req.getAiEventId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("AI event not found"));
        }
        AiFeedback feedback = new AiFeedback();
        feedback.setTenantId(tenantId);
        feedback.setAiEventId(req.getAiEventId());
        feedback.setFeatureType(req.getFeatureType() == null ? "AI_EVENT" : req.getFeatureType());
        feedback.setUserId(userId);
        feedback.setCorrect(Boolean.TRUE.equals(req.getIsCorrect()));
        feedback.setFeedbackType(req.getFeedbackType() == null
                ? (Boolean.TRUE.equals(req.getIsCorrect()) ? "AGREE" : "DISAGREE")
                : req.getFeedbackType());
        feedback.setComments(req.getComments());
        aiFeedbackRepository.save(feedback);
        auditService.record(tenantId, userId, username, "SUBMIT_AI_FEEDBACK", "AI_FEEDBACK",
                String.valueOf(req.getAiEventId()), "SUCCESS",
                "correct=" + req.getIsCorrect());
    }

    // ---------------------------------------------------------------------
    // ETA prediction (deterministic + optional ML)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public EtaResponseDto predictEta(Long tenantId, EtaRequestDto req) {
        accessPolicy.requireVehicle(tenantId, req.getVehicleId());
        double distanceKm = haversineKm(req.getOriginLat(), req.getOriginLng(),
                req.getDestinationLat(), req.getDestinationLng());

        double currentSpeed = req.getCurrentSpeedKph() != null && req.getCurrentSpeedKph() > 1
                ? req.getCurrentSpeedKph()
                : currentSpeedFor(tenantId, req.getVehicleId());

        String source = "RULE";
        double durationMinutes;
        double confidence;

        Map<String, Object> mlResult = tryPythonEta(tenantId, req, distanceKm, currentSpeed);
        if (mlResult != null && mlResult.get("estimated_duration_minutes") != null) {
            durationMinutes = ((Number) mlResult.get("estimated_duration_minutes")).doubleValue();
            confidence = mlResult.get("confidence") != null
                    ? ((Number) mlResult.get("confidence")).doubleValue() : 0.7;
            source = "ML";
        } else {
            double effectiveSpeed = Math.max(currentSpeed, 8.0); // avoid divide-by-zero / crawl
            durationMinutes = (distanceKm / effectiveSpeed) * 60.0;
            confidence = 0.55; // deterministic fallback is less certain
        }

        Map<String, Object> factors = new HashMap<>();
        factors.put("source", source);
        factors.put("distanceKm", round1(distanceKm));
        factors.put("assumedSpeedKph", round1(currentSpeed));

        String explanation = String.format(
                "%s ETA: %.1f km remaining at ~%.0f km/h -> ~%.0f min.",
                source.equals("ML") ? "Model-based" : "Rule-based",
                distanceKm, currentSpeed, durationMinutes);

        return EtaResponseDto.builder()
                .vehicleId(req.getVehicleId())
                .estimatedDistanceKm(round1(distanceKm))
                .estimatedDurationMinutes(round1(durationMinutes))
                .predictedArrivalTime(Instant.now().plusSeconds((long) (durationMinutes * 60)))
                .trafficDelayMinutes(0.0)
                .confidence(confidence)
                .factors(factors)
                .structuredExplanation(explanation)
                .build();
    }

    private Map<String, Object> tryPythonEta(Long tenantId, EtaRequestDto req,
                                             double distanceKm, double currentSpeed) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("tenant_id", tenantId);
            payload.put("vehicle_id", req.getVehicleId());
            payload.put("origin_lat", req.getOriginLat());
            payload.put("origin_lng", req.getOriginLng());
            payload.put("destination_lat", req.getDestinationLat());
            payload.put("destination_lng", req.getDestinationLng());
            payload.put("current_speed_kph", currentSpeed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = pythonAiClient.post("/v1/eta/predict", payload, Map.class);
            return resp;
        } catch (Exception ex) {
            log.debug("Python ETA unavailable, using rule fallback: {}", ex.getMessage());
            return null;
        }
    }

    private double currentSpeedFor(Long tenantId, Long vehicleId) {
        return currentPositionRepository.findByTenantId(tenantId).stream()
                .filter(p -> vehicleId.equals(p.getVehicleId()))
                .map(DeviceCurrentPosition::getSpeed)
                .findFirst()
                .orElse(30.0);
    }

    // ---------------------------------------------------------------------
    // Driver behaviour
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DriverScoreDto driverScore(Long tenantId, Long driverId) {
        Driver driver = accessPolicy.requireDriver(tenantId, driverId);
        return driverScoreRepository
                .findByTenantIdAndDriverIdOrderByScoreDateDesc(tenantId, driverId).stream()
                .findFirst()
                .map(s -> toDriverDto(s, driver.getName()))
                .orElseGet(() -> DriverScoreDto.builder()
                        .driverId(driverId)
                        .driverName(driver.getName())
                        .scoreDate(LocalDate.now(ZoneOffset.UTC))
                        .scorePeriod("DAILY")
                        .safetyScore(100.0)
                        .efficiencyScore(100.0)
                        .complianceScore(100.0)
                        .overallScore(100.0)
                        .grade("N/A")
                        .aiCoachingAdvice("Insufficient trip history to score this driver yet.")
                        .build());
    }

    // ---------------------------------------------------------------------
    // Geofence suggestions
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GeofenceSuggestionDto> geofenceSuggestions(Long tenantId) {
        return geofenceSuggestionRepository.findByTenantIdAndStatus(tenantId, "PENDING").stream()
                .map(this::toGeofenceDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void approveGeofenceSuggestion(Long tenantId, Long userId, String username, Long id) {
        GeofenceSuggestion suggestion = geofenceSuggestionRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence suggestion not found"));
        suggestion.setStatus("APPROVED");
        geofenceSuggestionRepository.save(suggestion);
        auditService.record(tenantId, userId, username, "APPROVE_GEOFENCE_SUGGESTION",
                "GEOFENCE_SUGGESTION", String.valueOf(id), "SUCCESS", suggestion.getSuggestedName());
    }

    @Transactional
    public void dismissGeofenceSuggestion(Long tenantId, Long userId, String username, Long id) {
        GeofenceSuggestion suggestion = geofenceSuggestionRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence suggestion not found"));
        suggestion.setStatus("DISMISSED");
        geofenceSuggestionRepository.save(suggestion);
        auditService.record(tenantId, userId, username, "DISMISS_GEOFENCE_SUGGESTION",
                "GEOFENCE_SUGGESTION", String.valueOf(id), "SUCCESS", null);
    }

    // ---------------------------------------------------------------------
    // Predictive maintenance
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaintenancePredictionDto> fleetMaintenance(Long tenantId) {
        List<MaintenancePrediction> preds = maintenanceRepository.findByTenantIdOrderByRiskScoreDesc(tenantId);
        Map<Long, String> names = vehicleNamesFor(tenantId,
                preds.stream().map(MaintenancePrediction::getVehicleId).toList());
        return preds.stream()
                .map(p -> toMaintenanceDto(p, names.getOrDefault(p.getVehicleId(), "Vehicle #" + p.getVehicleId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MaintenancePredictionDto> maintenanceForDevice(Long tenantId, Long deviceId) {
        Device device = accessPolicy.requireDevice(tenantId, deviceId);
        Long vehicleId = device.getVehicleId();
        if (vehicleId == null) {
            return List.of();
        }
        String vehicleName = vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .map(Vehicle::getName).orElse("Vehicle #" + vehicleId);
        return maintenanceRepository
                .findFirstByTenantIdAndVehicleIdOrderByCreatedAtDesc(tenantId, vehicleId)
                .map(p -> List.of(toMaintenanceDto(p, vehicleName)))
                .orElseGet(List::of);
    }

    // ---------------------------------------------------------------------
    // Intelligent dispatch (deterministic ranking; assignment needs confirmation)
    // ---------------------------------------------------------------------

    @Transactional
    public DispatchRecommendResponseDto dispatchRecommend(Long tenantId, Long userId,
                                                          String username,
                                                          DispatchRecommendRequestDto req) {
        List<Vehicle> candidates;
        if (req.getCandidateVehicleIds() != null && !req.getCandidateVehicleIds().isEmpty()) {
            candidates = req.getCandidateVehicleIds().stream()
                    .map(id -> accessPolicy.requireVehicle(tenantId, id))
                    .collect(Collectors.toList());
        } else {
            candidates = vehicleRepository.findAll().stream()
                    .filter(v -> tenantId.equals(v.getTenantId()))
                    .collect(Collectors.toList());
        }

        Map<Long, DeviceCurrentPosition> byVehicle = currentPositionRepository.findByTenantId(tenantId)
                .stream()
                .filter(p -> p.getVehicleId() != null)
                .collect(Collectors.toMap(DeviceCurrentPosition::getVehicleId, p -> p, (a, b) -> a));

        List<DispatchRecommendResponseDto.RankedVehicleDto> ranked = new ArrayList<>();
        for (Vehicle v : candidates) {
            DeviceCurrentPosition pos = byVehicle.get(v.getId());
            if (pos == null) {
                continue; // no live position -> not dispatchable
            }
            double distanceKm = haversineKm(req.getOriginLat(), req.getOriginLng(),
                    pos.getLatitude(), pos.getLongitude());
            double etaMinutes = (distanceKm / 30.0) * 60.0; // conservative urban avg
            double matchScore = Math.max(0.0, 100.0 - distanceKm * 2.0);
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format("%.1f km from pickup", distanceKm));
            reasons.add(String.format("~%.0f min ETA", etaMinutes));
            ranked.add(DispatchRecommendResponseDto.RankedVehicleDto.builder()
                    .vehicleId(v.getId())
                    .name(v.getName())
                    .matchScore(round1(matchScore))
                    .distanceToOriginKm(round1(distanceKm))
                    .etaToOriginMinutes(round1(etaMinutes))
                    .reasons(reasons)
                    .build());
        }
        ranked.sort(Comparator.comparingDouble(
                DispatchRecommendResponseDto.RankedVehicleDto::getDistanceToOriginKm));
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setRank(i + 1);
        }

        String topReason = ranked.isEmpty()
                ? "No vehicle with a live position is available for dispatch."
                : String.format("%s is closest at %.1f km (~%.0f min).",
                        ranked.get(0).getName(), ranked.get(0).getDistanceToOriginKm(),
                        ranked.get(0).getEtaToOriginMinutes());

        persistDispatchRecommendation(tenantId, req, ranked, topReason);
        auditService.record(tenantId, userId, username, "AI_DISPATCH_RECOMMEND", "DISPATCH",
                null, "SUCCESS", "candidates=" + ranked.size());

        return DispatchRecommendResponseDto.builder()
                .rankedVehicles(ranked)
                .topRecommendationReason(topReason)
                .build();
    }

    private void persistDispatchRecommendation(Long tenantId, DispatchRecommendRequestDto req,
                                               List<DispatchRecommendResponseDto.RankedVehicleDto> ranked,
                                               String topReason) {
        try {
            DispatchRecommendation rec = new DispatchRecommendation();
            rec.setTenantId(tenantId);
            rec.setJobDescription(req.getJobDescription());
            rec.setOriginLat(req.getOriginLat());
            rec.setOriginLng(req.getOriginLng());
            rec.setDestinationLat(req.getDestinationLat());
            rec.setDestinationLng(req.getDestinationLng());
            rec.setRankedVehiclesJson(objectMapper.writeValueAsString(ranked));
            rec.setRecommendationReason(topReason);
            dispatchRepository.save(rec);
        } catch (Exception ex) {
            // Persisting the audit copy must never fail the recommendation itself.
            log.warn("Could not persist dispatch recommendation: {}", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------------

    private Map<Long, String> vehicleNamesFor(Long tenantId, List<Long> vehicleIds) {
        List<Long> ids = vehicleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return vehicleRepository.findAllById(ids).stream()
                .filter(v -> tenantId.equals(v.getTenantId()))
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getName));
    }

    private AiEventDto toDto(AiEvent e, Map<Long, String> vehicleNames) {
        return AiEventDto.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .vehicleId(e.getVehicleId())
                .vehicleName(e.getVehicleId() != null ? vehicleNames.get(e.getVehicleId()) : null)
                .deviceId(e.getDeviceId())
                .driverId(e.getDriverId())
                .eventType(e.getEventType())
                .severity(e.getSeverity())
                .score(e.getScore())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .speed(e.getSpeed())
                .deviationPathJson(e.getDeviationPathJson())
                .reentryPointJson(e.getReentryPointJson())
                .explanation(e.getExplanation())
                .evidenceJson(e.getEvidenceJson())
                .acknowledged(e.isAcknowledged())
                .acknowledgedBy(e.getAcknowledgedBy())
                .acknowledgedAt(e.getAcknowledgedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private DriverScoreDto toDriverDto(DriverScoreDaily s, String driverName) {
        return DriverScoreDto.builder()
                .id(s.getId())
                .driverId(s.getDriverId())
                .driverName(driverName)
                .vehicleId(s.getVehicleId())
                .scoreDate(s.getScoreDate())
                .scorePeriod(s.getScorePeriod())
                .safetyScore(s.getSafetyScore())
                .efficiencyScore(s.getEfficiencyScore())
                .complianceScore(s.getComplianceScore())
                .overallScore(s.getOverallScore())
                .grade(gradeFor(s.getOverallScore()))
                .totalDistanceKm(s.getTotalDistanceKm())
                .totalDrivingMinutes(s.getTotalDrivingMinutes())
                .harshAccelCount(s.getHarshAccelCount())
                .harshBrakeCount(s.getHarshBrakeCount())
                .sharpTurnCount(s.getSharpTurnCount())
                .speedingSeconds(s.getSpeedingSeconds())
                .excessiveIdleMinutes(s.getExcessiveIdleMinutes())
                .anomaliesCount(s.getAnomaliesCount())
                .breakdownJson(s.getBreakdownJson())
                .aiCoachingAdvice(coachingFor(s))
                .build();
    }

    private GeofenceSuggestionDto toGeofenceDto(GeofenceSuggestion g) {
        return GeofenceSuggestionDto.builder()
                .id(g.getId())
                .suggestedName(g.getSuggestedName())
                .centerLatitude(g.getCenterLatitude())
                .centerLongitude(g.getCenterLongitude())
                .suggestedRadiusMeters(g.getSuggestedRadiusMeters())
                .clusterPointCount(g.getClusterPointCount())
                .confidence(g.getConfidence())
                .reasoning(g.getReasoning())
                .polygonJson(g.getPolygonJson())
                .status(g.getStatus())
                .build();
    }

    private MaintenancePredictionDto toMaintenanceDto(MaintenancePrediction p, String vehicleName) {
        List<String> actions = p.getRecommendedAction() == null || p.getRecommendedAction().isBlank()
                ? List.of()
                : List.of(p.getRecommendedAction().split("\\r?\\n"));
        return MaintenancePredictionDto.builder()
                .id(p.getId())
                .vehicleId(p.getVehicleId())
                .vehicleName(vehicleName)
                .riskScore(p.getRiskScore())
                .riskLevel(p.getRiskLevel())
                .predictedFailureDate(p.getPredictedFailureDate())
                .predictedDaysRemaining(p.getPredictedDaysRemaining())
                .odometerAtPrediction(p.getOdometerAtPrediction())
                .engineHoursAtPrediction(p.getEngineHoursAtPrediction())
                .batteryHealth(p.getBatteryHealth())
                .drivingStressFactor(p.getDrivingStressFactor())
                .recommendedActions(actions)
                .reasoning(p.getReasoning())
                .status(p.getStatus())
                .build();
    }

    private static String gradeFor(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "E";
    }

    private static String coachingFor(DriverScoreDaily s) {
        if (s.getHarshBrakeCount() >= s.getHarshAccelCount() && s.getHarshBrakeCount() > 0) {
            return "Focus on anticipating stops to reduce harsh braking.";
        }
        if (s.getHarshAccelCount() > 0) {
            return "Ease onto the accelerator to smooth out harsh acceleration.";
        }
        if (s.getSpeedingSeconds() > 0) {
            return "Reduce time spent over the speed limit to lift the compliance score.";
        }
        if (s.getExcessiveIdleMinutes() > 0) {
            return "Cut excessive idling to improve efficiency.";
        }
        return "Consistent, safe driving - keep it up.";
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Great-circle distance in kilometres. */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
