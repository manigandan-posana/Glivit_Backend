package com.glivt.ai.tools;

import com.glivt.access.FleetAccessPolicy;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.entity.DriverScoreDaily;
import com.glivt.ai.entity.GeofenceSuggestion;
import com.glivt.ai.entity.MaintenancePrediction;
import com.glivt.ai.entity.TripFeatureSnapshot;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.DriverScoreDailyRepository;
import com.glivt.ai.repository.GeofenceSuggestionRepository;
import com.glivt.ai.repository.MaintenancePredictionRepository;
import com.glivt.ai.repository.TripFeatureSnapshotRepository;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.Driver;
import com.glivt.driver.DriverRepository;
import com.glivt.event.Event;
import com.glivt.event.EventRepository;
import com.glivt.geofence.Geofence;
import com.glivt.geofence.GeofenceRepository;
import com.glivt.group.DeviceGroup;
import com.glivt.group.DeviceGroupRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.project.Project;
import com.glivt.project.ProjectRepository;
import com.glivt.report.ReportJob;
import com.glivt.report.ReportRepository;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.PermissionKeys;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every read-only accessor the assistant may call, and the schema it advertises
 * to the model.
 *
 * <p>Adding a capability means adding a tool here. Nothing about phrasing,
 * keywords or intent is encoded anywhere: the model reads the descriptions and
 * chooses. That is what lets "vehicle list", "which lorries do we have" and
 * "show me the fleet" all work without a synonym table.
 *
 * <p>Every accessor resolves its tenant from the authenticated principal and is
 * gated on a permission, so the assistant is strictly a different presentation
 * of data the user could already open a screen to see.
 */
@Component
public class AiToolRegistry {

    /** Hard ceiling on rows returned to the model, to bound the prompt. */
    private static final int MAX_ROWS = 40;

    /**
     * Drops null entries before a row reaches the model.
     *
     * <p>A JSON null renders as the literal "null" in the model's answer
     * ("Speed: null"), which reads like a bug to the user. Omitting the key
     * instead lets the model phrase the absence naturally.
     */
    private static Map<String, Object> compact(Map<String, Object> row) {
        row.values().removeIf(java.util.Objects::isNull);
        return row;
    }

    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final AiEventRepository aiEventRepository;
    private final EventRepository eventRepository;
    private final GeofenceRepository geofenceRepository;
    private final GeofenceSuggestionRepository geofenceSuggestionRepository;
    private final MaintenancePredictionRepository maintenanceRepository;
    private final DriverScoreDailyRepository driverScoreRepository;
    private final TripFeatureSnapshotRepository tripRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final DeviceGroupRepository groupRepository;
    private final ProjectRepository projectRepository;
    private final FleetAccessPolicy fleetAccessPolicy;

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public AiToolRegistry(VehicleRepository vehicleRepository,
            DeviceRepository deviceRepository,
            DriverRepository driverRepository,
            DeviceCurrentPositionRepository currentPositionRepository,
            AiEventRepository aiEventRepository,
            EventRepository eventRepository,
            GeofenceRepository geofenceRepository,
            GeofenceSuggestionRepository geofenceSuggestionRepository,
            MaintenancePredictionRepository maintenanceRepository,
            DriverScoreDailyRepository driverScoreRepository,
            TripFeatureSnapshotRepository tripRepository,
            ReportRepository reportRepository,
            UserRepository userRepository,
            DeviceGroupRepository groupRepository,
            ProjectRepository projectRepository,
            FleetAccessPolicy fleetAccessPolicy) {
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.aiEventRepository = aiEventRepository;
        this.eventRepository = eventRepository;
        this.geofenceRepository = geofenceRepository;
        this.geofenceSuggestionRepository = geofenceSuggestionRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.driverScoreRepository = driverScoreRepository;
        this.tripRepository = tripRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.projectRepository = projectRepository;
        this.fleetAccessPolicy = fleetAccessPolicy;
        registerAll();
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    private void register(AiTool tool) {
        tools.put(tool.name(), tool);
    }

    private void registerAll() {
        register(simple("get_fleet_status",
                "Overall fleet summary: how many vehicles and tracked devices there are, and how "
                        + "many are running, idle, stopped or have no recent data. Use for "
                        + "'fleet status', 'overview', 'how many vehicles', 'what is online'.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::fleetStatus));

        register(simple("list_vehicles",
                "The list of vehicles in the fleet with name, registration number, category, "
                        + "current status, speed and last update. Use for 'vehicle list', "
                        + "'show me the vehicles', 'what vehicles do we have'.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listVehicles));

        register(withArgs("get_vehicle",
                "Full detail for one vehicle: status, live location, speed, ignition, fuel, "
                        + "odometer, engine hours and assigned device. Match by name or "
                        + "registration number.",
                PermissionKeys.VIEW_LIVE_LOCATION,
                Map.of("vehicle", Map.of("type", "string",
                        "description", "Vehicle name or registration number")),
                List.of("vehicle"), this::getVehicle));

        register(simple("list_live_positions",
                "Live map data: the current latitude, longitude, speed, heading and address of "
                        + "every tracked vehicle. Use for 'live map', 'where is everyone', "
                        + "'current positions'.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listLivePositions));

        register(simple("list_devices",
                "GPS devices with IMEI, assigned vehicle, status, expiry and when each last "
                        + "reported. Use for 'device list', 'tracker health', 'which devices are "
                        + "offline'.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listDevices));

        register(simple("list_drivers",
                "Drivers with name, phone, licence number and expiry, and whether they are active.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listDrivers));

        register(simple("get_driver_scores",
                "Latest safety, efficiency and compliance scores per driver, with risk level, "
                        + "contributing harsh-driving counts and when each was calculated. Use for "
                        + "'driver scores', 'who is my riskiest driver', 'driver safety'.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::driverScores));

        register(withArgs("list_alerts",
                "AI-detected anomaly incidents: type, severity, vehicle, how many times it "
                        + "recurred, when it was first and last seen, and the explanation. Use for "
                        + "'alerts', 'anomalies', 'what went wrong', 'incidents'.",
                PermissionKeys.VIEW_LIVE_LOCATION,
                Map.of("severity", Map.of("type", "string",
                        "description", "Optional filter: LOW, MEDIUM, HIGH or CRITICAL")),
                List.of(), this::listAlerts));

        register(simple("list_operational_events",
                "Non-AI operational events such as geofence entry/exit, ignition and power alerts.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listOperationalEvents));

        register(simple("list_geofences",
                "Geofences with name, shape, radius, whether entry/exit alerts are on, and any "
                        + "speed rule. Use for 'geofences', 'zones', 'sites', 'boundaries'.",
                PermissionKeys.MANAGE_GEOFENCES, this::listGeofences));

        register(simple("list_geofence_suggestions",
                "AI-suggested geofences awaiting review, with visit count, average stop duration "
                        + "and confidence.",
                PermissionKeys.MANAGE_GEOFENCES, this::listGeofenceSuggestions));

        register(simple("list_maintenance_predictions",
                "Predictive maintenance per vehicle: risk level, predicted component, days or "
                        + "kilometres remaining, reasoning, confidence and whether it came from a "
                        + "trained model or deterministic rules.",
                PermissionKeys.VIEW_LIVE_LOCATION, this::listMaintenance));

        register(withArgs("list_trips",
                "Completed trips with distance, duration, idling, maximum speed, harsh events and "
                        + "route deviations. Use for 'trip history', 'route history', "
                        + "'how far did we travel'.",
                PermissionKeys.VIEW_LIVE_LOCATION,
                Map.of("days", Map.of("type", "integer",
                        "description", "How many days back to look, default 7")),
                List.of(), this::listTrips));

        register(simple("list_reports",
                "Generated and scheduled reports with type, status, period and creation time. Use "
                        + "for 'reports', 'my exports'.",
                PermissionKeys.VIEW_REPORTS, this::listReports));

        register(simple("list_users",
                "User accounts in this organisation with username, name, role and status. Part of "
                        + "the Management area.",
                PermissionKeys.MANAGE_USERS, this::listUsers));

        register(simple("list_groups",
                "Device groups with name and how many devices each contains.",
                PermissionKeys.MANAGE_GROUPS, this::listGroups));

        register(simple("list_projects",
                "Projects with name and description, used to organise devices and users.",
                PermissionKeys.MANAGE_PROJECTS, this::listProjects));

        register(simple("get_app_capabilities",
                "What the Glivt app can do and which screen performs each task. Use when the user "
                        + "asks how to do something rather than for data.",
                PermissionKeys.VIEW_LIVE_LOCATION, (user, args) -> appCapabilities()));
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Tools this specific user is allowed to use, in Ollama tool-schema form. */
    public List<Map<String, Object>> schemasFor(AppUserPrincipal user) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (AiTool tool : tools.values()) {
            if (!user.hasPermission(tool.requiredPermission())) {
                // Not advertised at all, so the model cannot even attempt it.
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", Map.of(
                    "type", "object",
                    "properties", tool.parameters(),
                    "required", tool.requiredParameters()));
            schemas.add(Map.of("type", "function", "function", function));
        }
        return schemas;
    }

    public Optional<AiTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Run a tool for a user.
     *
     * <p>Permission is re-checked here, not just at advertising time: a model
     * that hallucinates a tool name it was never offered still cannot reach data.
     */
    @Transactional(readOnly = true)
    public Object execute(AppUserPrincipal user, String name, Map<String, Object> arguments) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            return Map.of("error", "No such tool: " + name);
        }
        if (!user.hasPermission(tool.requiredPermission())) {
            return Map.of("error", "You do not have permission to access this information.");
        }
        try {
            return tool.execute(user, arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            // A broken tool must degrade to "unavailable", never leak a stack trace.
            return Map.of("error", "This information could not be retrieved right now.");
        }
    }

    // ------------------------------------------------------------------
    // Tool implementations. Each is tenant-scoped via the principal.
    // ------------------------------------------------------------------

    private Object fleetStatus(AppUserPrincipal user, Map<String, Object> args) {
        Long tenantId = user.getTenantId();
        List<DeviceCurrentPosition> positions = visiblePositions(user);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeviceState state : DeviceState.values()) {
            counts.put(state.name(), 0);
        }
        for (DeviceCurrentPosition position : positions) {
            counts.merge(position.getState().name(), 1, Integer::sum);
        }
        return Map.of(
                "totalVehicles", vehicleRepository.countByTenantId(tenantId),
                "totalDevices", deviceRepository.countByTenantId(tenantId),
                "trackedNow", positions.size(),
                "statusCounts", counts,
                "unacknowledgedAiAlerts",
                        aiEventRepository.countByTenantIdAndAcknowledgedFalse(tenantId));
    }

    private Object listVehicles(AppUserPrincipal user, Map<String, Object> args) {
        Long tenantId = user.getTenantId();
        Map<Long, DeviceCurrentPosition> byVehicle = positionsByVehicle(user);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findByTenantId(tenantId)) {
            if (!canSeeVehicle(user, vehicle.getId())) {
                continue;
            }
            DeviceCurrentPosition position = byVehicle.get(vehicle.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", vehicle.getName());
            row.put("registration", vehicle.getRegistrationNumber());
            row.put("category", vehicle.getCategory() == null ? null : vehicle.getCategory().name());
            row.put("status", position == null ? "NO_DATA" : position.getState().name());
            row.put("speedKph", position == null ? null : Math.round(position.getSpeed()));
            row.put("lastUpdate", position == null || position.getDeviceTime() == null
                    ? null : position.getDeviceTime().toString());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "vehicles", rows);
    }

    private Object getVehicle(AppUserPrincipal user, Map<String, Object> args) {
        String needle = string(args.get("vehicle"));
        if (needle == null || needle.isBlank()) {
            return Map.of("error", "Specify a vehicle name or registration number.");
        }
        String wanted = needle.trim().toLowerCase(Locale.ROOT);
        Optional<Vehicle> match = vehicleRepository.findByTenantId(user.getTenantId()).stream()
                .filter(v -> canSeeVehicle(user, v.getId()))
                .filter(v -> matches(v.getName(), wanted) || matches(v.getRegistrationNumber(), wanted))
                .findFirst();
        if (match.isEmpty()) {
            return Map.of("found", false, "message", "No vehicle matches '" + needle + "'.");
        }

        Vehicle vehicle = match.get();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("found", true);
        row.put("name", vehicle.getName());
        row.put("registration", vehicle.getRegistrationNumber());
        row.put("category", vehicle.getCategory() == null ? null : vehicle.getCategory().name());
        row.put("odometerKm", vehicle.getOdometer());
        row.put("engineHours", vehicle.getEngineHours());

        positionsByVehicle(user).entrySet().stream()
                .filter(entry -> entry.getKey().equals(vehicle.getId()))
                .findFirst()
                .ifPresent(entry -> {
                    DeviceCurrentPosition position = entry.getValue();
                    row.put("status", position.getState().name());
                    row.put("speedKph", Math.round(position.getSpeed()));
                    row.put("latitude", position.getLatitude());
                    row.put("longitude", position.getLongitude());
                    row.put("address", position.getAddress());
                    row.put("ignitionOn", position.getIgnition());
                    row.put("fuelLevelPercent", position.getFuelLevel());
                    row.put("lastUpdate", position.getDeviceTime() == null
                            ? null : position.getDeviceTime().toString());
                });
        row.putIfAbsent("status", "NO_DATA");
        return compact(row);
    }

    private Object listLivePositions(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeviceCurrentPosition position : visiblePositions(user)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicle", vehicleLabel(user.getTenantId(), position.getVehicleId()));
            row.put("status", position.getState().name());
            row.put("latitude", position.getLatitude());
            row.put("longitude", position.getLongitude());
            row.put("speedKph", Math.round(position.getSpeed()));
            row.put("address", position.getAddress());
            row.put("lastUpdate", position.getDeviceTime() == null
                    ? null : position.getDeviceTime().toString());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "positions", rows);
    }

    private Object listDevices(AppUserPrincipal user, Map<String, Object> args) {
        Instant staleBefore = Instant.now().minus(Duration.ofHours(2));
        Map<Long, DeviceCurrentPosition> byDevice = new LinkedHashMap<>();
        for (DeviceCurrentPosition position : visiblePositions(user)) {
            byDevice.put(position.getDeviceId(), position);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Device device : deviceRepository.findByTenantId(user.getTenantId())) {
            if (!fleetAccessPolicy.deviceScope(user).allows(device.getId())) {
                continue;
            }
            DeviceCurrentPosition position = byDevice.get(device.getId());
            Instant last = position == null ? null : position.getServerTime();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", device.getName());
            row.put("imei", device.getImei());
            row.put("vehicle", vehicleLabel(user.getTenantId(), device.getVehicleId()));
            row.put("status", device.getStatus() == null ? null : device.getStatus().name());
            row.put("expiryDate", device.getExpiryDate() == null ? null : device.getExpiryDate().toString());
            row.put("lastPacket", last == null ? "never" : last.toString());
            row.put("reportingRecently", last != null && last.isAfter(staleBefore));
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "devices", rows);
    }

    private Object listDrivers(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Driver driver : driverRepository.findByTenantId(user.getTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", driver.getName());
            row.put("identifier", driver.getIdentifier());
            row.put("phone", driver.getPhone());
            row.put("licenceNumber", driver.getLicenceNumber());
            row.put("licenceExpiry", driver.getLicenceExpiry() == null
                    ? null : driver.getLicenceExpiry().toString());
            row.put("active", driver.isActive());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "drivers", rows);
    }

    private Object driverScores(AppUserPrincipal user, Map<String, Object> args) {
        Long tenantId = user.getTenantId();
        Map<Long, DriverScoreDaily> latest = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            for (DriverScoreDaily score : driverScoreRepository
                    .findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
                            tenantId, today.minusDays(dayOffset), "DAILY")) {
                latest.putIfAbsent(score.getDriverId(), score);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int unscored = 0;
        for (Driver driver : driverRepository.findByTenantId(tenantId)) {
            DriverScoreDaily score = latest.get(driver.getId());
            if (score == null) {
                unscored++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("driver", driver.getName());
            row.put("overallScore", score.getOverallScore());
            row.put("safetyScore", score.getSafetyScore());
            row.put("complianceScore", score.getComplianceScore());
            row.put("efficiencyScore", score.getEfficiencyScore());
            row.put("riskLevel", score.getRiskLevel());
            row.put("harshBraking", score.getHarshBrakeCount());
            row.put("harshAcceleration", score.getHarshAccelCount());
            row.put("speedingMinutes", score.getSpeedingSeconds() / 60);
            row.put("scoreDate", score.getScoreDate().toString());
            // Provenance matters: a rule score must not read as a model score.
            row.put("source", score.getSource());
            rows.add(compact(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scoredDrivers", rows.size());
        result.put("driversWithoutScoreYet", unscored);
        result.put("scores", rows);
        if (rows.isEmpty()) {
            result.put("note", "No driver has a score yet. Scores are produced by the nightly job "
                    + "once drivers have completed trips.");
        }
        return result;
    }

    private Object listAlerts(AppUserPrincipal user, Map<String, Object> args) {
        String severity = string(args.get("severity"));
        List<AiEvent> events = aiEventRepository.findFiltered(user.getTenantId(), null,
                severity == null || severity.isBlank() ? null : severity.toUpperCase(Locale.ROOT),
                null, PageRequest.of(0, MAX_ROWS)).getContent();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiEvent event : events) {
            if (!canSeeVehicle(user, event.getVehicleId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.getEventType());
            row.put("severity", event.getSeverity());
            row.put("vehicle", vehicleLabel(user.getTenantId(), event.getVehicleId()));
            row.put("status", event.getStatus());
            row.put("occurrences", event.getOccurrenceCount());
            row.put("firstSeen", event.getFirstObservedAt() == null
                    ? null : event.getFirstObservedAt().toString());
            row.put("lastSeen", event.getLastObservedAt() == null
                    ? null : event.getLastObservedAt().toString());
            row.put("explanation", event.getExplanation());
            row.put("acknowledged", event.isAcknowledged());
            rows.add(compact(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("alerts", rows);
        if (rows.isEmpty()) {
            result.put("note", "No AI alerts have been recorded for this fleet.");
        }
        return result;
    }

    private Object listOperationalEvents(AppUserPrincipal user, Map<String, Object> args) {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event event : eventRepository.findByTenantIdAndServerTimeAfter(user.getTenantId(), since)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.getEventType());
            row.put("severity", event.getSeverity());
            row.put("vehicle", vehicleLabel(user.getTenantId(), event.getVehicleId()));
            row.put("at", event.getServerTime() == null ? null : event.getServerTime().toString());
            row.put("detail", event.getDetail());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "events", rows, "windowDays", 7);
    }

    private Object listGeofences(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Geofence geofence : geofenceRepository.findByTenantIdAndActiveTrue(user.getTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", geofence.getName());
            row.put("type", geofence.getType());
            row.put("radiusMeters", geofence.getRadiusMeters());
            row.put("entryAlert", geofence.isEnterAlert());
            row.put("exitAlert", geofence.isExitAlert());
            row.put("speedLimitKph", geofence.getSpeedLimitKph());
            row.put("description", geofence.getDescription());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "geofences", rows);
    }

    private Object listGeofenceSuggestions(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GeofenceSuggestion suggestion
                : geofenceSuggestionRepository.findByTenantIdAndStatus(user.getTenantId(), "PENDING")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("suggestedName", suggestion.getSuggestedName());
            row.put("visitCount", suggestion.getVisitCount());
            row.put("averageStopMinutes", suggestion.getAverageStopMinutes());
            row.put("radiusMeters", suggestion.getSuggestedRadiusMeters());
            row.put("confidence", suggestion.getConfidence());
            rows.add(compact(row));
        }
        return Map.of("count", rows.size(), "suggestions", rows);
    }

    private Object listMaintenance(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaintenancePrediction prediction
                : maintenanceRepository.findByTenantIdOrderByRiskScoreDesc(user.getTenantId())) {
            if (!canSeeVehicle(user, prediction.getVehicleId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicle", vehicleLabel(user.getTenantId(), prediction.getVehicleId()));
            row.put("riskLevel", prediction.getRiskLevel());
            row.put("component", prediction.getPredictedComponent());
            row.put("daysRemaining", prediction.getPredictedDaysRemaining());
            row.put("kmRemaining", prediction.getRemainingKm());
            row.put("reasoning", prediction.getReasoning());
            row.put("confidence", prediction.getConfidence());
            row.put("source", prediction.getSource());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("predictions", rows);
        if (rows.isEmpty()) {
            result.put("note", "No maintenance predictions yet. They are generated by the daily "
                    + "job once vehicles report odometer or engine hours.");
        }
        return result;
    }

    private Object listTrips(AppUserPrincipal user, Map<String, Object> args) {
        int days = intValue(args.get("days"), 7);
        Instant since = Instant.now().minus(Math.max(1, Math.min(days, 90)), ChronoUnit.DAYS);
        List<Map<String, Object>> rows = new ArrayList<>();
        double totalKm = 0;
        for (TripFeatureSnapshot trip
                : tripRepository.findByTenantIdAndStartTimeAfter(user.getTenantId(), since)) {
            if (!canSeeVehicle(user, trip.getVehicleId())) {
                continue;
            }
            totalKm += trip.getDistanceKm();
            if (rows.size() < MAX_ROWS) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vehicle", vehicleLabel(user.getTenantId(), trip.getVehicleId()));
                row.put("start", trip.getStartTime() == null ? null : trip.getStartTime().toString());
                row.put("distanceKm", trip.getDistanceKm());
                row.put("durationMinutes", trip.getDurationMinutes());
                row.put("idleMinutes", trip.getIdleDurationMinutes());
                row.put("maxSpeedKph", trip.getMaxSpeedKph());
                row.put("harshEvents", trip.getHarshEventCount());
                row.put("routeDeviations", trip.getRouteDeviationCount());
                rows.add(compact(row));
            }
        }
        return Map.of("windowDays", days, "tripCount", rows.size(),
                "totalDistanceKm", Math.round(totalKm * 10) / 10.0, "trips", rows);
    }

    private Object listReports(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportJob report : reportRepository.findByTenantIdOrderByCreatedAtDesc(
                user.getTenantId(), PageRequest.of(0, MAX_ROWS)).getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", report.getReportType());
            row.put("status", report.getStatus() == null ? null : report.getStatus().name());
            row.put("format", report.getOutputFormat());
            row.put("createdAt", report.getCreatedAt() == null ? null : report.getCreatedAt().toString());
            rows.add(compact(row));
        }
        return Map.of("count", rows.size(), "reports", rows);
    }

    private Object listUsers(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User account : userRepository.findByTenantId(user.getTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", account.getUsername());
            row.put("name", account.getName());
            row.put("role", account.getRole() == null ? null : account.getRole().name());
            row.put("status", account.getStatus() == null ? null : account.getStatus().name());
            // Deliberately no email, phone or password metadata.
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "users", rows);
    }

    private Object listGroups(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeviceGroup group : groupRepository.findByTenantId(user.getTenantId())) {
            rows.add(Map.of(
                    "name", group.getName(),
                    "deviceCount",
                    deviceRepository.countByTenantIdAndGroupId(user.getTenantId(), group.getId())));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "groups", rows);
    }

    private Object listProjects(AppUserPrincipal user, Map<String, Object> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project project : projectRepository.findByTenantId(user.getTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", project.getName());
            row.put("description", project.getDescription());
            rows.add(compact(row));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return Map.of("count", rows.size(), "projects", rows);
    }

    private Object appCapabilities() {
        return Map.of("screens", List.of(
                Map.of("screen", "Map", "does", "Live vehicle positions, layers, geofence overlays"),
                Map.of("screen", "Live track", "does", "Follow one vehicle in real time"),
                Map.of("screen", "Trip playback", "does", "Replay a past journey"),
                Map.of("screen", "Vehicles", "does", "Vehicle and device list and detail"),
                Map.of("screen", "Geofences", "does", "Create, edit and approve zones"),
                Map.of("screen", "Reports", "does", "Trip, stop, distance and idling reports with export"),
                Map.of("screen", "Commands", "does", "Send device commands; needs permission and confirmation"),
                Map.of("screen", "Management", "does", "Users, drivers, groups and projects"),
                Map.of("screen", "AI Command Centre", "does", "Alerts, driver scores, maintenance, chat"),
                Map.of("screen", "AI Tools", "does", "ETA prediction, dispatch ranking, search, diagnostics")));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<DeviceCurrentPosition> visiblePositions(AppUserPrincipal user) {
        FleetAccessPolicy.VehicleScope scope = fleetAccessPolicy.vehicleScope(user);
        return currentPositionRepository.findByTenantId(user.getTenantId()).stream()
                .filter(position -> scope.unrestricted() || scope.allows(position.getVehicleId()))
                .toList();
    }

    private Map<Long, DeviceCurrentPosition> positionsByVehicle(AppUserPrincipal user) {
        Map<Long, DeviceCurrentPosition> byVehicle = new LinkedHashMap<>();
        for (DeviceCurrentPosition position : visiblePositions(user)) {
            if (position.getVehicleId() != null) {
                byVehicle.putIfAbsent(position.getVehicleId(), position);
            }
        }
        return byVehicle;
    }

    private boolean canSeeVehicle(AppUserPrincipal user, Long vehicleId) {
        if (vehicleId == null) {
            return true;
        }
        FleetAccessPolicy.VehicleScope scope = fleetAccessPolicy.vehicleScope(user);
        return scope.unrestricted() || scope.allows(vehicleId);
    }

    private String vehicleLabel(Long tenantId, Long vehicleId) {
        if (vehicleId == null) {
            return "Unassigned";
        }
        return vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .map(Vehicle::getName)
                .filter(name -> !name.isBlank())
                .orElse("Vehicle #" + vehicleId);
    }

    private static boolean matches(String value, String wanted) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(wanted);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // -- small functional adapters so each tool is one method reference ----

    @FunctionalInterface
    private interface ToolBody {
        Object apply(AppUserPrincipal user, Map<String, Object> arguments);
    }

    private static AiTool simple(String name, String description, String permission, ToolBody body) {
        return withArgs(name, description, permission, Map.of(), List.of(), body);
    }

    private static AiTool withArgs(String name, String description, String permission,
            Map<String, Object> parameters, List<String> required, ToolBody body) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                return parameters;
            }

            @Override
            public List<String> requiredParameters() {
                return required;
            }

            @Override
            public String requiredPermission() {
                return permission;
            }

            @Override
            public Object execute(AppUserPrincipal user, Map<String, Object> arguments) {
                return body.apply(user, arguments);
            }
        };
    }
}
