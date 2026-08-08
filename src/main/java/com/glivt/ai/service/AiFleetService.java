package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
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
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.driver.Driver;
import com.glivt.geofence.GeofenceService;
import com.glivt.geofence.dto.GeofenceDto;
import com.glivt.geofence.dto.GeofenceRequest;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * views.
 *
 * <p>Every method resolves data strictly within the caller's tenant. ML work is
 * delegated to the Python AI service through {@link PythonAiClient}; when that
 * service is unavailable each operation degrades to an explainable deterministic
 * result and says so via {@code source}, so the UI never presents a rule result
 * as a model prediction.
 *
 * <p>Chat lives in {@link AiChatService}; there is no direct Ollama access here
 * or anywhere else in the backend.
 */
@Service
public class AiFleetService {

    private static final Logger log = LoggerFactory.getLogger(AiFleetService.class);
    private static final Set<DeviceState> ACTIVE_STATES = Set.of(DeviceState.RUNNING, DeviceState.STOPPED,
            DeviceState.IDLE);
    private static final Set<String> HIGH_RISK_LEVELS = Set.of("HIGH", "CRITICAL");
    private static final double RISKY_DRIVER_THRESHOLD = 60.0;
    /** Beyond this age a live position is too stale to dispatch against. */
    private static final Duration DISPATCH_POSITION_MAX_AGE = Duration.ofMinutes(30);

    private final AiEventRepository aiEventRepository;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final DriverScoreDailyRepository driverScoreRepository;
    private final GeofenceSuggestionRepository geofenceSuggestionRepository;
    private final MaintenancePredictionRepository maintenanceRepository;
    private final DispatchRecommendationRepository dispatchRepository;
    private final VehicleRepository vehicleRepository;
    private final com.glivt.driver.DriverRepository driverRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final GeofenceService geofenceService;
    private final FleetAccessPolicy accessPolicy;
    private final AuditService auditService;
    private final PythonAiClient pythonAiClient;
    private final DriverAssignmentResolver driverAssignmentResolver;
    private final AiGovernanceService governanceService;
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
            com.glivt.driver.DriverRepository driverRepository,
            DeviceCurrentPositionRepository currentPositionRepository,
            GeofenceService geofenceService,
            FleetAccessPolicy accessPolicy,
            AuditService auditService,
            PythonAiClient pythonAiClient,
            DriverAssignmentResolver driverAssignmentResolver,
            AiGovernanceService governanceService) {
        this.aiEventRepository = aiEventRepository;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.driverScoreRepository = driverScoreRepository;
        this.geofenceSuggestionRepository = geofenceSuggestionRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.dispatchRepository = dispatchRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.geofenceService = geofenceService;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.pythonAiClient = pythonAiClient;
        this.driverAssignmentResolver = driverAssignmentResolver;
        this.governanceService = governanceService;
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
    // AI events / incidents
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
            // The incident stays visible but moves out of the OPEN queue.
            event.setStatus(AiEvent.STATUS_ACKNOWLEDGED);
            event = aiEventRepository.save(event);
        }
        auditService.record(tenantId, userId, username, "ACKNOWLEDGE_AI_EVENT", "AI_EVENT",
                String.valueOf(eventId), "SUCCESS", "eventType=" + event.getEventType());
        return toDto(event, vehicleNamesFor(tenantId, List.of(event.getVehicleId())));
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
    // ETA prediction
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public EtaResponseDto predictEta(Long tenantId, EtaRequestDto req) {
        accessPolicy.requireVehicle(tenantId, req.getVehicleId());

        double straightLineKm = haversineKm(req.getOriginLat(), req.getOriginLng(),
                req.getDestinationLat(), req.getDestinationLng());
        double currentSpeed = req.getCurrentSpeedKph() != null && req.getCurrentSpeedKph() > 1
                ? req.getCurrentSpeedKph()
                : currentSpeedFor(tenantId, req.getVehicleId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("vehicle_id", req.getVehicleId());
        payload.put("origin_lat", req.getOriginLat());
        payload.put("origin_lng", req.getOriginLng());
        payload.put("destination_lat", req.getDestinationLat());
        payload.put("destination_lng", req.getDestinationLng());
        payload.put("current_speed_kph", currentSpeed);
        // Real road distance when the caller resolved one from the routing layer.
        if (req.getRoadDistanceKm() != null && req.getRoadDistanceKm() > 0) {
            payload.put("road_distance_km", req.getRoadDistanceKm());
        }
        payload.put("traffic_available", false);

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/eta/predict", payload,
                new PythonAiClient.AiCallOptions("eta.predict", tenantId, req.getVehicleId(), 0));

        if (result.success()) {
            Map<String, Object> body = result.payload();
            double durationMinutes = asDouble(body.get("estimated_duration_minutes"), 0);
            double distanceKm = asDouble(body.get("estimated_distance_km"), straightLineKm);
            double confidence = asDouble(body.get("confidence"), 0.7);
            double rangeMinutes = asDouble(body.get("range_minutes"), durationMinutes * 0.15);
            double lateProbability = asDouble(body.get("late_probability"), 0.2);
            String distanceSource = asString(body.getOrDefault("distance_source",
                    "STRAIGHT_LINE_ADJUSTED"));
            String trafficInput = asString(body.getOrDefault("traffic_input", "NONE"));
            String source = asString(body.getOrDefault("source", "RULE"));

            List<String> reasons = new ArrayList<>();
            if (body.get("reasons") instanceof List<?> list) {
                list.forEach(o -> reasons.add(String.valueOf(o)));
            }

            Map<String, Object> factors = new HashMap<>();
            factors.put("source", source);
            factors.put("distanceKm", round1(distanceKm));
            factors.put("distanceSource", distanceSource);
            factors.put("trafficInput", trafficInput);
            factors.put("assumedSpeedKph", round1(currentSpeed));
            factors.put("lateProbability", round2(lateProbability));
            factors.put("rangeMinutes", round1(rangeMinutes));
            factors.put("reasons", reasons);
            factors.put("calculatedAt", Instant.now().toString());

            governanceService.record(tenantId, "eta.predict", source, null,
                    asString(body.get("model_version")), asString(body.get("rule_version")),
                    result.durationMs(), null, "VEHICLE", req.getVehicleId());

            return EtaResponseDto.builder()
                    .vehicleId(req.getVehicleId())
                    .estimatedDistanceKm(round1(distanceKm))
                    .estimatedDurationMinutes(round1(durationMinutes))
                    .predictedArrivalTime(Instant.now().plusSeconds((long) (durationMinutes * 60)))
                    .trafficDelayMinutes(round1(asDouble(body.get("traffic_delay_minutes"), 0)))
                    .confidence(confidence)
                    .factors(factors)
                    .source(source)
                    .distanceSource(distanceSource)
                    .trafficInput(trafficInput)
                    .rangeMinutes(round1(rangeMinutes))
                    .lateProbability(round2(lateProbability))
                    .calculatedAt(Instant.now())
                    .structuredExplanation(String.format(
                            "Model-based ETA: %.1f km at ~%.0f km/h -> ~%.0f min (+/-%.0f). "
                                    + "Late probability %.0f%%.%s",
                            distanceKm, currentSpeed, durationMinutes, rangeMinutes,
                            lateProbability * 100,
                            reasons.isEmpty() ? "" : " " + reasons.get(0)))
                    .build();
        }

        // Deterministic fallback, clearly labelled as such.
        double distanceKm = straightLineKm * 1.3;
        double effectiveSpeed = Math.max(currentSpeed, 8.0);
        double durationMinutes = (distanceKm / effectiveSpeed) * 60.0;
        double rangeMinutes = durationMinutes * 0.25;
        double lateProbability = currentSpeed < 25 ? 0.40 : 0.20;

        Map<String, Object> factors = new HashMap<>();
        factors.put("source", "RULE");
        factors.put("distanceKm", round1(distanceKm));
        factors.put("distanceSource", "STRAIGHT_LINE_ADJUSTED");
        factors.put("trafficInput", "NONE");
        factors.put("assumedSpeedKph", round1(currentSpeed));
        factors.put("lateProbability", round2(lateProbability));
        factors.put("rangeMinutes", round1(rangeMinutes));
        factors.put("degradedReason", result.errorCode().name());
        factors.put("calculatedAt", Instant.now().toString());

        governanceService.record(tenantId, "eta.predict", "RULE", null, null, "eta-fallback-1.0",
                result.durationMs(), result.errorCode().name(), "VEHICLE", req.getVehicleId());

        return EtaResponseDto.builder()
                .vehicleId(req.getVehicleId())
                .estimatedDistanceKm(round1(distanceKm))
                .estimatedDurationMinutes(round1(durationMinutes))
                .predictedArrivalTime(Instant.now().plusSeconds((long) (durationMinutes * 60)))
                .trafficDelayMinutes(0.0)
                .confidence(0.5)
                .factors(factors)
                .source("RULE")
                .distanceSource("STRAIGHT_LINE_ADJUSTED")
                .trafficInput("NONE")
                .rangeMinutes(round1(rangeMinutes))
                .lateProbability(round2(lateProbability))
                .calculatedAt(Instant.now())
                .structuredExplanation(String.format(
                        "Rule-based ETA (AI service unavailable): %.1f km at ~%.0f km/h -> ~%.0f min "
                                + "(+/-%.0f), from straight-line distance.",
                        distanceKm, currentSpeed, durationMinutes, rangeMinutes))
                .build();
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
                        // No trips scored yet: report "no data" rather than a
                        // flattering perfect score.
                        .safetyScore(0.0)
                        .efficiencyScore(0.0)
                        .complianceScore(0.0)
                        .overallScore(0.0)
                        .grade("N/A")
                        .riskLevel("UNKNOWN")
                        .hasScore(false)
                        .source("NONE")
                        .aiCoachingAdvice("Insufficient trip history to score this driver yet.")
                        .build());
    }

    /**
     * Every driver the caller may see, each with their latest score when one
     * exists. This is what lets the command centre offer a real driver picker
     * instead of hard-coding an id.
     */
    @Transactional(readOnly = true)
    public List<DriverScoreDto> driverScoreboard(Long tenantId) {
        Map<Long, DriverScoreDaily> latest = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            for (DriverScoreDaily score : driverScoreRepository
                    .findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
                            tenantId, today.minusDays(dayOffset), "DAILY")) {
                latest.putIfAbsent(score.getDriverId(), score);
            }
        }

        List<DriverScoreDto> scoreboard = new ArrayList<>();
        for (Driver driver : driverRepository.findByTenantId(tenantId)) {
            DriverScoreDaily score = latest.get(driver.getId());
            if (score != null) {
                scoreboard.add(toDriverDto(score, driver.getName()));
            } else {
                // Listed, but explicitly marked as having no score yet rather
                // than shown with a flattering default.
                scoreboard.add(DriverScoreDto.builder()
                        .driverId(driver.getId())
                        .driverName(driver.getName())
                        .scoreDate(today)
                        .scorePeriod("DAILY")
                        .grade("N/A")
                        .riskLevel("UNKNOWN")
                        .source("NONE")
                        .hasScore(false)
                        .aiCoachingAdvice("No completed trips scored for this driver yet.")
                        .build());
            }
        }
        scoreboard.sort(Comparator
                .comparing(DriverScoreDto::isHasScore).reversed()
                .thenComparingDouble(DriverScoreDto::getOverallScore));
        return scoreboard;
    }

    /** Score history for one driver, newest first - powers the trend chart. */
    @Transactional(readOnly = true)
    public List<DriverScoreDto> driverScoreTrend(Long tenantId, Long driverId, int days) {
        Driver driver = accessPolicy.requireDriver(tenantId, driverId);
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(1, days));
        return driverScoreRepository
                .findByTenantIdAndDriverIdOrderByScoreDateDesc(tenantId, driverId).stream()
                .filter(s -> !s.getScoreDate().isBefore(from))
                .map(s -> toDriverDto(s, driver.getName()))
                .collect(Collectors.toList());
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
    public GeofenceDto approveGeofenceSuggestion(Long tenantId, Long userId, String username, Long id,
            String overrideName, Double overrideRadiusMeters) {
        GeofenceSuggestion suggestion = geofenceSuggestionRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence suggestion not found"));
        if (!"PENDING".equalsIgnoreCase(suggestion.getStatus())) {
            throw new BadRequestException("Geofence suggestion has already been processed");
        }

        // Allow the reviewer to edit before approving.
        String name = overrideName != null && !overrideName.isBlank()
                ? overrideName.trim()
                : suggestion.getSuggestedName();
        double radius = overrideRadiusMeters != null && overrideRadiusMeters > 0
                ? overrideRadiusMeters
                : suggestion.getSuggestedRadiusMeters();

        if (!Double.isFinite(suggestion.getCenterLatitude())
                || !Double.isFinite(suggestion.getCenterLongitude())
                || !Double.isFinite(radius) || radius <= 0) {
            throw new BadRequestException("Geofence suggestion coordinates are invalid");
        }

        GeofenceRequest request = new GeofenceRequest(
                name,
                blankToNull(suggestion.getReasoning()),
                "#27D34D",
                "CIRCLE",
                List.of(List.of(suggestion.getCenterLongitude(), suggestion.getCenterLatitude())),
                radius,
                null,
                List.of(),
                List.of(),
                true,
                true,
                null,
                true);
        GeofenceDto created = geofenceService.create(tenantId, userId, username, request);
        suggestion.setStatus("APPROVED");
        geofenceSuggestionRepository.save(suggestion);
        auditService.record(tenantId, userId, username, "APPROVE_GEOFENCE_SUGGESTION",
                "GEOFENCE_SUGGESTION", String.valueOf(id), "SUCCESS",
                name + " -> geofence " + created.id());
        return created;
    }

    @Transactional
    public void dismissGeofenceSuggestion(Long tenantId, Long userId, String username, Long id) {
        GeofenceSuggestion suggestion = geofenceSuggestionRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence suggestion not found"));
        suggestion.setStatus("DISMISSED");
        suggestion.setDismissedBy(userId);
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
    // Intelligent dispatch - ranked by the AI service; assignment always needs
    // an explicit, separately authorised confirmation.
    // ---------------------------------------------------------------------

    @Transactional
    public DispatchRecommendResponseDto dispatchRecommend(Long tenantId, Long userId,
            String username, DispatchRecommendRequestDto req) {

        List<Vehicle> candidates;
        if (req.getCandidateVehicleIds() != null && !req.getCandidateVehicleIds().isEmpty()) {
            // requireVehicle enforces tenant ownership of every supplied id.
            candidates = req.getCandidateVehicleIds().stream()
                    .map(id -> accessPolicy.requireVehicle(tenantId, id))
                    .collect(Collectors.toList());
        } else {
            candidates = vehicleRepository.findByTenantId(tenantId);
        }

        Map<Long, DeviceCurrentPosition> byVehicle = currentPositionRepository.findByTenantId(tenantId)
                .stream()
                .filter(p -> p.getVehicleId() != null)
                .collect(Collectors.toMap(DeviceCurrentPosition::getVehicleId, p -> p, (a, b) -> a));

        Map<Long, Double> driverScores = latestDriverScores(tenantId);
        Map<Long, String> maintenanceRisk = latestMaintenanceRisk(tenantId);

        List<Map<String, Object>> aiCandidates = new ArrayList<>();
        Instant now = Instant.now();
        for (Vehicle vehicle : candidates) {
            DeviceCurrentPosition position = byVehicle.get(vehicle.getId());
            if (position == null) {
                continue; // no live position -> not dispatchable
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("vehicle_id", vehicle.getId());
            candidate.put("name", vehicle.getName());
            candidate.put("category", vehicle.getCategory() == null ? "CAR" : vehicle.getCategory().name());
            candidate.put("current_lat", position.getLatitude());
            candidate.put("current_lng", position.getLongitude());
            candidate.put("fuel_level_percent", position.getFuelLevel());
            candidate.put("battery_percent", position.getBattery());
            candidate.put("available", position.getState() != DeviceState.NO_DATA);
            candidate.put("job_status", position.getState().name());
            candidate.put("maintenance_risk_level", maintenanceRisk.getOrDefault(vehicle.getId(), "LOW"));

            driverAssignmentResolver.resolve(tenantId, vehicle.getId(), now).ifPresent(driver -> {
                candidate.put("driver_id", driver.driverId());
                candidate.put("driver_safety_score", driverScores.get(driver.driverId()));
            });

            if (position.getServerTime() != null) {
                candidate.put("position_age_seconds",
                        Duration.between(position.getServerTime(), now).getSeconds());
            }
            aiCandidates.add(candidate);
        }

        List<DispatchRecommendResponseDto.RankedVehicleDto> ranked;
        String topReason;
        String source;

        if (aiCandidates.isEmpty()) {
            ranked = List.of();
            topReason = "No vehicle with a live position is available for dispatch.";
            source = "RULE";
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenant_id", tenantId);
            payload.put("job_description", req.getJobDescription());
            payload.put("origin_lat", req.getOriginLat());
            payload.put("origin_lng", req.getOriginLng());
            payload.put("destination_lat", req.getDestinationLat());
            payload.put("destination_lng", req.getDestinationLng());
            payload.put("required_category", req.getRequiredCategory());
            payload.put("candidates", aiCandidates);

            AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/dispatch/rank",
                    payload, PythonAiClient.AiCallOptions.of("dispatch.rank", tenantId));

            if (result.success()) {
                ranked = parseRanked(result.payload());
                topReason = asString(result.payload().get("top_recommendation_reason"));
                source = "PYTHON_AI";
            } else {
                ranked = fallbackRanking(req, aiCandidates);
                topReason = ranked.isEmpty()
                        ? "No vehicle with a live position is available for dispatch."
                        : String.format("%s is closest at %.1f km (~%.0f min). "
                                        + "Ranked by distance only - the AI ranking service is unavailable.",
                                ranked.get(0).getName(), ranked.get(0).getDistanceToOriginKm(),
                                ranked.get(0).getEtaToOriginMinutes());
                source = "RULE";
            }
            governanceService.record(tenantId, "dispatch.rank", source, null, null, null,
                    result.durationMs(), result.success() ? null : result.errorCode().name(),
                    "DISPATCH", null);
        }

        persistDispatchRecommendation(tenantId, req, ranked, topReason);
        auditService.record(tenantId, userId, username, "AI_DISPATCH_RECOMMEND", "DISPATCH",
                null, "SUCCESS", "candidates=" + ranked.size() + " source=" + source);

        return DispatchRecommendResponseDto.builder()
                .rankedVehicles(ranked)
                .topRecommendationReason(topReason)
                .source(source)
                // AI only recommends. Assigning a vehicle is a separate,
                // permission-checked action the user must confirm.
                .requiresConfirmation(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<DispatchRecommendResponseDto.RankedVehicleDto> parseRanked(Map<String, Object> body) {
        List<DispatchRecommendResponseDto.RankedVehicleDto> ranked = new ArrayList<>();
        if (!(body.get("ranked_vehicles") instanceof List<?> list)) {
            return ranked;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            List<String> reasons = new ArrayList<>();
            if (item.get("reasons") instanceof List<?> reasonList) {
                reasonList.forEach(r -> reasons.add(String.valueOf(r)));
            }
            ranked.add(DispatchRecommendResponseDto.RankedVehicleDto.builder()
                    .vehicleId(asLong(item.get("vehicle_id")))
                    .name(asString(item.get("name")))
                    .matchScore(asDouble(item.get("match_score"), 0))
                    .distanceToOriginKm(asDouble(item.get("distance_to_origin_km"), 0))
                    .etaToOriginMinutes(asDouble(item.get("eta_to_origin_minutes"), 0))
                    .rank((int) asDouble(item.get("rank"), 0))
                    .eligible(!Boolean.FALSE.equals(item.get("eligible")))
                    .driverId(asLong(item.get("driver_id")))
                    .driverSafetyScore(item.get("driver_safety_score") == null
                            ? null : asDouble(item.get("driver_safety_score"), 0))
                    .maintenanceRiskLevel(asString(item.getOrDefault("maintenance_risk_level", "LOW")))
                    .reasons(reasons)
                    .build());
        }
        return ranked;
    }

    /** Distance-only ranking used when the AI ranking service is unavailable. */
    private List<DispatchRecommendResponseDto.RankedVehicleDto> fallbackRanking(
            DispatchRecommendRequestDto req, List<Map<String, Object>> candidates) {
        List<DispatchRecommendResponseDto.RankedVehicleDto> ranked = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            double distanceKm = haversineKm(req.getOriginLat(), req.getOriginLng(),
                    asDouble(candidate.get("current_lat"), 0), asDouble(candidate.get("current_lng"), 0));
            double etaMinutes = (distanceKm / 32.0) * 60.0;
            ranked.add(DispatchRecommendResponseDto.RankedVehicleDto.builder()
                    .vehicleId(asLong(candidate.get("vehicle_id")))
                    .name(asString(candidate.get("name")))
                    .matchScore(round1(Math.max(0.0, 100.0 - distanceKm * 2.0)))
                    .distanceToOriginKm(round1(distanceKm))
                    .etaToOriginMinutes(round1(etaMinutes))
                    .eligible(true)
                    .maintenanceRiskLevel(asString(candidate.getOrDefault("maintenance_risk_level", "LOW")))
                    .reasons(List.of(String.format("%.1f km from pickup", distanceKm),
                            String.format("~%.0f min ETA", etaMinutes),
                            "Distance-only ranking: the AI ranking service is unavailable."))
                    .build());
        }
        ranked.sort(Comparator.comparingDouble(
                DispatchRecommendResponseDto.RankedVehicleDto::getDistanceToOriginKm));
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setRank(i + 1);
        }
        return ranked;
    }

    private Map<Long, Double> latestDriverScores(Long tenantId) {
        Map<Long, Double> scores = new HashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int dayOffset = 0; dayOffset < 3 && scores.isEmpty(); dayOffset++) {
            driverScoreRepository
                    .findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
                            tenantId, today.minusDays(dayOffset), "DAILY")
                    .forEach(score -> scores.putIfAbsent(score.getDriverId(), score.getOverallScore()));
        }
        return scores;
    }

    private Map<Long, String> latestMaintenanceRisk(Long tenantId) {
        Map<Long, String> risk = new HashMap<>();
        for (MaintenancePrediction prediction
                : maintenanceRepository.findByTenantIdOrderByRiskScoreDesc(tenantId)) {
            risk.putIfAbsent(prediction.getVehicleId(), prediction.getRiskLevel());
        }
        return risk;
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
                .status(e.getStatus())
                .occurrenceCount(e.getOccurrenceCount())
                .firstObservedAt(e.getFirstObservedAt())
                .lastObservedAt(e.getLastObservedAt())
                .relatedEventTypes(AiAsyncEvaluatorService.splitTypes(e.getRelatedEventTypes()))
                .routeId(e.getRouteId())
                .distanceFromRouteMeters(e.getDistanceFromRouteMeters())
                .speedLimitKph(e.getSpeedLimitKph())
                .speedLimitSource(e.getSpeedLimitSource())
                .source(e.getSource())
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
                .grade(s.getGrade() != null ? s.getGrade() : gradeFor(s.getOverallScore()))
                .riskLevel(s.getRiskLevel())
                .totalDistanceKm(s.getTotalDistanceKm())
                .totalDrivingMinutes(s.getTotalDrivingMinutes())
                .harshAccelCount(s.getHarshAccelCount())
                .harshBrakeCount(s.getHarshBrakeCount())
                .sharpTurnCount(s.getSharpTurnCount())
                .speedingSeconds(s.getSpeedingSeconds())
                .excessiveIdleMinutes(s.getExcessiveIdleMinutes())
                .anomaliesCount(s.getAnomaliesCount())
                .breakdownJson(s.getBreakdownJson())
                .reasonsJson(s.getReasonsJson())
                .source(s.getSource())
                .ruleVersion(s.getRuleVersion())
                .modelVersion(s.getModelVersion())
                .calculatedAt(s.getCalculatedAt())
                .hasScore(true)
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
                .visitCount(g.getVisitCount())
                .averageStopMinutes(g.getAverageStopMinutes())
                .firstVisitAt(g.getFirstVisitAt())
                .lastVisitAt(g.getLastVisitAt())
                .distinctVehicleCount(g.getDistinctVehicleCount())
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
                .predictedComponent(p.getPredictedComponent())
                .remainingKm(p.getRemainingKm())
                .odometerAtPrediction(p.getOdometerAtPrediction())
                .engineHoursAtPrediction(p.getEngineHoursAtPrediction())
                .batteryHealth(p.getBatteryHealth())
                .drivingStressFactor(p.getDrivingStressFactor())
                .recommendedActions(actions)
                .reasoning(p.getReasoning())
                .confidence(p.getConfidence())
                .source(p.getSource())
                .evaluatedAt(p.getEvaluatedAt())
                .status(p.getStatus())
                .build();
    }

    private static String gradeFor(double score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 80) {
            return "B";
        }
        if (score >= 70) {
            return "C";
        }
        if (score >= 60) {
            return "D";
        }
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /** Great-circle distance in kilometres. */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        return GeoMath.haversineKm(lat1, lon1, lat2, lon2);
    }
}
