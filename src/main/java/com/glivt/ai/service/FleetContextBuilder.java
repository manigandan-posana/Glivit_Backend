package com.glivt.ai.service;

import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.entity.DriverScoreDaily;
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
import com.glivt.geofence.GeofenceRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.PermissionKeys;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import com.glivt.user.UserRepository;
import com.glivt.project.ProjectRepository;
import com.glivt.command.CommandRepository;
import com.glivt.tenant.TenantRepository;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.client.AiResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the tenant-filtered fleet snapshot that grounds a chat answer.
 */
@Service
public class FleetContextBuilder {

    private static final int MAX_ALERTS = 8;
    private static final int MAX_VEHICLES_LISTED = 15;
    private static final int MAX_TRIPS = 5;

    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final AiEventRepository aiEventRepository;
    private final MaintenancePredictionRepository maintenanceRepository;
    private final DriverScoreDailyRepository driverScoreRepository;
    private final TripFeatureSnapshotRepository tripRepository;
    private final GeofenceRepository geofenceRepository;
    private final GeofenceSuggestionRepository geofenceSuggestionRepository;
    
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final CommandRepository commandRepository;
    private final TenantRepository tenantRepository;
    private final PythonAiClient pythonAiClient;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FleetContextBuilder.class);

    public FleetContextBuilder(VehicleRepository vehicleRepository,
            DeviceRepository deviceRepository,
            DriverRepository driverRepository,
            DeviceCurrentPositionRepository currentPositionRepository,
            AiEventRepository aiEventRepository,
            MaintenancePredictionRepository maintenanceRepository,
            DriverScoreDailyRepository driverScoreRepository,
            TripFeatureSnapshotRepository tripRepository,
            GeofenceRepository geofenceRepository,
            GeofenceSuggestionRepository geofenceSuggestionRepository,
            UserRepository userRepository,
            ProjectRepository projectRepository,
            CommandRepository commandRepository,
            TenantRepository tenantRepository,
            PythonAiClient pythonAiClient) {
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.aiEventRepository = aiEventRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.driverScoreRepository = driverScoreRepository;
        this.tripRepository = tripRepository;
        this.geofenceRepository = geofenceRepository;
        this.geofenceSuggestionRepository = geofenceSuggestionRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.commandRepository = commandRepository;
        this.tenantRepository = tenantRepository;
        this.pythonAiClient = pythonAiClient;
    }

    public record Citation(String type, Long id, String label) {
    }

    public record FleetContext(Map<String, Object> context, List<Citation> citations,
            String deterministicAnswer) {
    }

    @Transactional(readOnly = true)
    public FleetContext build(AppUserPrincipal user, ChatIntent intent, Long selectedVehicleId,
            String question) {
        Long tenantId = user.getTenantId();
        Map<String, Object> context = new LinkedHashMap<>();
        List<Citation> citations = new ArrayList<>();

        context.put("asOf", Instant.now().toString());
        context.put("intent", intent.name());

        Vehicle selectedVehicle = null;
        if (selectedVehicleId != null) {
            selectedVehicle = vehicleRepository.findByIdAndTenantId(selectedVehicleId, tenantId)
                    .orElse(null);
        }

        // Pre-load all tenant vehicles and drivers to eliminate N+1 queries in loops
        List<Vehicle> tenantVehicles = vehicleRepository.findByTenantId(tenantId);
        Map<Long, String> vehicleNameMap = tenantVehicles.stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getName, (a, b) -> a));

        List<Driver> tenantDrivers = driverRepository.findByTenantId(tenantId);
        Map<Long, String> driverNameMap = tenantDrivers.stream()
                .collect(Collectors.toMap(Driver::getId, Driver::getName, (a, b) -> a));

        // Add app knowledge document if matched (lexical or semantic search)
        String appKnowledge = searchAppKnowledge(tenantId, question);
        if (appKnowledge != null) {
            context.put("app_knowledge", appKnowledge);
        }

        String deterministic = switch (intent) {
            case FLEET_STATUS -> fleetStatus(tenantId, vehicleNameMap, context, citations);
            case VEHICLE_STATUS -> vehicleDetail(tenantId, selectedVehicle, intent, vehicleNameMap, context, citations, question);
            case RECENT_ALERTS -> recentAlerts(tenantId, selectedVehicle, vehicleNameMap, context, citations);
            case MAINTENANCE -> maintenance(user, vehicleNameMap, context, citations);
            case DRIVER_SAFETY -> driverSafety(user, driverNameMap, context, citations);
            case FUEL -> fuel(tenantId, vehicleNameMap, context, citations);
            case ROUTE_HISTORY -> routeHistory(tenantId, selectedVehicle, context, citations);
            case ETA -> etaGuidance(tenantId, selectedVehicle, vehicleNameMap, context, citations);
            case DISPATCH -> dispatchGuidance(tenantId, vehicleNameMap, context, citations);
            case REPORT_SUMMARY -> reportSummary(user, tenantId, context);
            case GEOFENCE -> geofences(user, tenantId, context);
            case DEVICE_HEALTH -> deviceHealth(tenantId, context, citations);
            case USERS -> users(tenantId, context);
            case PROJECTS -> projects(tenantId, context);
            case COMMANDS -> commands(tenantId, context);
            case TENANTS -> tenants(tenantId, context);
            case CURRENT_LOCATION -> vehicleDetail(tenantId, selectedVehicle, intent, vehicleNameMap, context, citations, question);
            case APP_HELP -> appHelp(context);
            case UNKNOWN -> unrecognised(context, question);
        };

        return new FleetContext(context, citations, deterministic);
    }

    private String fleetStatus(Long tenantId, Map<Long, String> vehicleNameMap, Map<String, Object> context, List<Citation> citations) {
        List<DeviceCurrentPosition> positions = currentPositionRepository.findByTenantId(tenantId);
        Map<DeviceState, Integer> counts = new EnumMap<>(DeviceState.class);
        for (DeviceCurrentPosition position : positions) {
            counts.merge(position.getState(), 1, Integer::sum);
        }
        long totalVehicles = vehicleRepository.countByTenantId(tenantId);
        long openAlerts = aiEventRepository.countByTenantIdAndAcknowledgedFalse(tenantId);

        Map<String, Object> statusCounts = new LinkedHashMap<>();
        for (DeviceState state : DeviceState.values()) {
            statusCounts.put(state.name().toLowerCase(java.util.Locale.ROOT), counts.getOrDefault(state, 0));
        }
        context.put("fleetTotals", Map.of(
                "vehicles", totalVehicles,
                "trackedDevices", positions.size(),
                "openAiAlerts", openAlerts));
        context.put("statusCounts", statusCounts);

        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (DeviceCurrentPosition position : positions.stream()
                .sorted(Comparator.comparing(DeviceCurrentPosition::getState))
                .limit(MAX_VEHICLES_LISTED)
                .toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String label = vehicleLabel(vehicleNameMap, position.getVehicleId());
            row.put("vehicle", label);
            row.put("status", position.getState().name());
            row.put("speedKph", Math.round(position.getSpeed()));
            row.put("lastUpdate", position.getDeviceTime() == null
                    ? "unknown" : position.getDeviceTime().toString());
            vehicles.add(row);
            if (position.getVehicleId() != null) {
                citations.add(new Citation("VEHICLE", position.getVehicleId(), label));
            }
        }
        context.put("vehicles", vehicles);

        return String.format(
                "Your fleet has %d vehicle(s) with %d tracked device(s): %d running, %d idle, "
                        + "%d stopped, %d with no recent data. There are %d unacknowledged AI alert(s).",
                totalVehicles, positions.size(),
                counts.getOrDefault(DeviceState.RUNNING, 0),
                counts.getOrDefault(DeviceState.IDLE, 0),
                counts.getOrDefault(DeviceState.STOPPED, 0),
                counts.getOrDefault(DeviceState.NO_DATA, 0),
                openAlerts);
    }

    private String vehicleDetail(Long tenantId, Vehicle vehicle, ChatIntent intent,
            Map<Long, String> vehicleNameMap, Map<String, Object> context, List<Citation> citations) {
        return vehicleDetail(tenantId, vehicle, intent, vehicleNameMap, context, citations, "");
    }

    private String vehicleDetail(Long tenantId, Vehicle vehicle, ChatIntent intent,
            Map<Long, String> vehicleNameMap, Map<String, Object> context, List<Citation> citations, String question) {
        if (vehicle == null) {
            context.put("selectedVehicle", null);

            // Populate all vehicle locations in context so the AI can answer general location questions or questions about any vehicle
            List<Map<String, Object>> locs = new ArrayList<>();
            for (DeviceCurrentPosition position : currentPositionRepository.findByTenantId(tenantId)) {
                String label = vehicleLabel(vehicleNameMap, position.getVehicleId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vehicle", label);
                row.put("status", position.getState().name());
                row.put("speedKph", Math.round(position.getSpeed()));
                row.put("address", position.getAddress());
                row.put("latitude", position.getLatitude());
                row.put("longitude", position.getLongitude());
                row.put("lastUpdate", position.getDeviceTime() == null ? null : position.getDeviceTime().toString());
                locs.add(row);
                if (position.getVehicleId() != null) {
                    citations.add(new Citation("VEHICLE", position.getVehicleId(), label));
                }
            }
            context.put("vehicleLocations", locs);

            // If the question is about specific vehicles or asking for all/running vehicle locations, return the detailed answer.
            // Otherwise, return "No vehicle is selected."
            String lowerQ = question != null ? question.toLowerCase(Locale.ROOT) : "";
            boolean isSpecificOrGeneral = lowerQ.contains("running") || lowerQ.contains("all") || lowerQ.contains("list") 
                    || lowerQ.contains("where is") && !lowerQ.contains("where is it") && !lowerQ.contains("where is the vehicle");
            
            if (isSpecificOrGeneral && !locs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Locations of your vehicles: ");
                int count = 0;
                for (Map<String, Object> loc : locs) {
                    if (count > 0) sb.append("; ");
                    String name = (String) loc.get("vehicle");
                    String status = (String) loc.get("status");
                    String addr = (String) loc.get("address");
                    if (addr == null || addr.isBlank()) {
                        addr = String.format(Locale.ROOT, "%.5f, %.5f", loc.get("latitude"), loc.get("longitude"));
                    }
                    sb.append(name).append(" is ").append(status.toLowerCase(Locale.ROOT)).append(" at ").append(addr);
                    count++;
                }
                return sb.toString();
            }

            context.put("note", "No vehicle is currently selected.");
            return "No vehicle is selected. Open a vehicle on the map or in the vehicle list, "
                    + "then ask again.";
        }
        citations.add(new Citation("VEHICLE", vehicle.getId(), vehicle.getName()));

        Optional<DeviceCurrentPosition> position = currentPositionRepository.findByTenantId(tenantId)
                .stream()
                .filter(p -> vehicle.getId().equals(p.getVehicleId()))
                .findFirst();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", vehicle.getName());
        detail.put("registration", vehicle.getRegistrationNumber());
        detail.put("category", vehicle.getCategory() == null ? null : vehicle.getCategory().name());
        detail.put("odometerKm", vehicle.getOdometer());
        detail.put("engineHours", vehicle.getEngineHours());

        if (position.isPresent()) {
            DeviceCurrentPosition p = position.get();
            detail.put("status", p.getState().name());
            detail.put("speedKph", Math.round(p.getSpeed()));
            detail.put("latitude", p.getLatitude());
            detail.put("longitude", p.getLongitude());
            detail.put("address", p.getAddress());
            detail.put("lastUpdate", p.getDeviceTime() == null ? null : p.getDeviceTime().toString());
            detail.put("ignitionOn", p.getIgnition());
            detail.put("fuelLevelPercent", p.getFuelLevel());
        } else {
            detail.put("status", "NO_DATA");
            detail.put("note", "No live position has been received for this vehicle.");
        }
        context.put("selectedVehicle", detail);

        if (position.isEmpty()) {
            return vehicle.getName() + " has no live position on record, so its current location "
                    + "and status are unavailable.";
        }
        DeviceCurrentPosition p = position.get();
        String where = p.getAddress() != null && !p.getAddress().isBlank()
                ? p.getAddress()
                : String.format(java.util.Locale.ROOT, "%.5f, %.5f", p.getLatitude(), p.getLongitude());
        if (intent == ChatIntent.CURRENT_LOCATION) {
            return String.format("%s was last reported at %s on %s, travelling %.0f km/h.",
                    vehicle.getName(), where,
                    p.getDeviceTime() == null ? "an unknown time" : p.getDeviceTime(),
                    p.getSpeed());
        }
        return String.format("%s is %s at %s, travelling %.0f km/h. Last update: %s.",
                vehicle.getName(), p.getState().name().toLowerCase(java.util.Locale.ROOT), where,
                p.getSpeed(), p.getDeviceTime() == null ? "unknown" : p.getDeviceTime());
    }

    private String recentAlerts(Long tenantId, Vehicle vehicle, Map<Long, String> vehicleNameMap,
            Map<String, Object> context, List<Citation> citations) {
        List<AiEvent> events = vehicle == null
                ? aiEventRepository.findTop10ByTenantIdOrderByCreatedAtDesc(tenantId)
                : aiEventRepository.findTop5ByTenantIdAndVehicleIdOrderByCreatedAtDesc(
                        tenantId, vehicle.getId());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiEvent event : events.stream().limit(MAX_ALERTS).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String label = vehicleLabel(vehicleNameMap, event.getVehicleId());
            row.put("type", event.getEventType());
            row.put("severity", event.getSeverity());
            row.put("vehicle", label);
            row.put("status", event.getStatus());
            row.put("occurrences", event.getOccurrenceCount());
            row.put("firstSeen", event.getFirstObservedAt() == null
                    ? null : event.getFirstObservedAt().toString());
            row.put("lastSeen", event.getLastObservedAt() == null
                    ? null : event.getLastObservedAt().toString());
            row.put("explanation", event.getExplanation());
            rows.add(row);
            citations.add(new Citation("AI_EVENT", event.getId(),
                    event.getEventType() + " - " + label));
        }
        context.put("recentAlerts", rows);
        context.put("unacknowledgedCount",
                aiEventRepository.countByTenantIdAndAcknowledgedFalse(tenantId));

        if (rows.isEmpty()) {
            return vehicle == null
                    ? "There are no AI alerts recorded for your fleet."
                    : "There are no AI alerts recorded for " + vehicle.getName() + ".";
        }
        Map<String, Object> first = rows.get(0);
        return String.format("There are %d recent alert(s). The latest is a %s %s on %s (%s).",
                rows.size(), first.get("severity"), first.get("type"), first.get("vehicle"),
                first.get("lastSeen"));
    }

    private String maintenance(AppUserPrincipal user, Map<Long, String> vehicleNameMap,
            Map<String, Object> context, List<Citation> citations) {
        if (!user.hasPermission(PermissionKeys.VIEW_LIVE_LOCATION)) {
            context.put("permissionDenied", "maintenance");
            return "You do not have permission to view maintenance information.";
        }
        Long tenantId = user.getTenantId();
        List<MaintenancePrediction> predictions =
                maintenanceRepository.findByTenantIdOrderByRiskScoreDesc(tenantId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaintenancePrediction prediction : predictions.stream().limit(MAX_ALERTS).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String label = vehicleLabel(vehicleNameMap, prediction.getVehicleId());
            row.put("vehicle", label);
            row.put("riskLevel", prediction.getRiskLevel());
            row.put("component", prediction.getPredictedComponent());
            row.put("daysRemaining", prediction.getPredictedDaysRemaining());
            row.put("reason", prediction.getReasoning());
            row.put("source", prediction.getSource());
            row.put("lastEvaluated", prediction.getEvaluatedAt() == null
                    ? null : prediction.getEvaluatedAt().toString());
            rows.add(row);
            citations.add(new Citation("VEHICLE", prediction.getVehicleId(), label));
        }
        context.put("maintenancePredictions", rows);

        if (rows.isEmpty()) {
            return "No maintenance predictions have been generated yet. They are produced by the "
                    + "scheduled maintenance job once vehicles have odometer and service history.";
        }
        Map<String, Object> top = rows.get(0);
        return String.format("%d vehicle(s) have maintenance predictions. Highest risk: %s (%s, %s), "
                        + "about %s day(s) remaining.",
                rows.size(), top.get("vehicle"), top.get("riskLevel"),
                top.get("component") == null ? "component not identified" : top.get("component"),
                top.get("daysRemaining"));
    }

    private String driverSafety(AppUserPrincipal user, Map<Long, String> driverNameMap,
            Map<String, Object> context, List<Citation> citations) {
        Long tenantId = user.getTenantId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DriverScoreDaily> scores = driverScoreRepository
                .findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(tenantId, today, "DAILY");
        if (scores.isEmpty()) {
            scores = driverScoreRepository.findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
                    tenantId, today.minusDays(1), "DAILY");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DriverScoreDaily score : scores.stream().limit(MAX_ALERTS).toList()) {
            String name = driverLabel(driverNameMap, score.getDriverId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("driver", name);
            row.put("overallScore", score.getOverallScore());
            row.put("safetyScore", score.getSafetyScore());
            row.put("complianceScore", score.getComplianceScore());
            row.put("efficiencyScore", score.getEfficiencyScore());
            row.put("riskLevel", score.getRiskLevel());
            row.put("harshBraking", score.getHarshBrakeCount());
            row.put("speedingSeconds", score.getSpeedingSeconds());
            row.put("scoreDate", score.getScoreDate().toString());
            row.put("source", score.getSource());
            rows.add(row);
            citations.add(new Citation("DRIVER", score.getDriverId(), name));
        }
        context.put("driverScores", rows);

        if (rows.isEmpty()) {
            long driverCount = driverRepository.countByTenantId(tenantId);
            context.put("driverCount", driverCount);
            return "No driver scores have been calculated yet. The daily scoring job produces them "
                    + "once completed trips exist for a driver.";
        }
        Map<String, Object> lowest = rows.get(0);
        return String.format("%d driver score(s) available. Lowest is %s at %s/100 (risk %s).",
                rows.size(), lowest.get("driver"), lowest.get("overallScore"), lowest.get("riskLevel"));
    }

    private String fuel(Long tenantId, Map<Long, String> vehicleNameMap, Map<String, Object> context, List<Citation> citations) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Double> levels = new ArrayList<>();
        for (DeviceCurrentPosition position : currentPositionRepository.findByTenantId(tenantId)) {
            if (position.getFuelLevel() == null) {
                continue;
            }
            levels.add(position.getFuelLevel());
            if (rows.size() < 15) {
                String label = vehicleLabel(vehicleNameMap, position.getVehicleId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vehicle", label);
                row.put("fuelLevelPercent", position.getFuelLevel());
                row.put("lastUpdate", position.getDeviceTime() == null
                        ? null : position.getDeviceTime().toString());
                rows.add(row);
                if (position.getVehicleId() != null) {
                    citations.add(new Citation("VEHICLE", position.getVehicleId(), label));
                }
            }
        }
        context.put("fuelLevels", rows);

        if (rows.isEmpty()) {
            context.put("note", "No device in this fleet reports a fuel level.");
            return "No vehicle in your fleet currently reports a fuel level, so fuel information "
                    + "is unavailable.";
        }
        double average = levels.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format("%d vehicle(s) report fuel level, averaging %.0f%%.", rows.size(), average);
    }

    private String routeHistory(Long tenantId, Vehicle vehicle, Map<String, Object> context,
            List<Citation> citations) {
        if (vehicle == null) {
            context.put("note", "No vehicle selected for trip history.");
            return "Select a vehicle to see its trip history.";
        }
        citations.add(new Citation("VEHICLE", vehicle.getId(), vehicle.getName()));
        List<TripFeatureSnapshot> trips = tripRepository
                .findByTenantIdAndVehicleIdOrderByStartTimeDesc(tenantId, vehicle.getId());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TripFeatureSnapshot trip : trips.stream().limit(MAX_TRIPS).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", trip.getStartTime() == null ? null : trip.getStartTime().toString());
            row.put("end", trip.getEndTime() == null ? null : trip.getEndTime().toString());
            row.put("distanceKm", trip.getDistanceKm());
            row.put("durationMinutes", trip.getDurationMinutes());
            row.put("idleMinutes", trip.getIdleDurationMinutes());
            row.put("maxSpeedKph", trip.getMaxSpeedKph());
            row.put("routeDeviations", trip.getRouteDeviationCount());
            rows.add(row);
        }
        context.put("recentTrips", rows);

        if (rows.isEmpty()) {
            return "No completed trips are recorded for " + vehicle.getName() + " yet.";
        }
        double totalKm = rows.stream().mapToDouble(r -> ((Number) r.get("distanceKm")).doubleValue()).sum();
        return String.format("%s has %d recent trip(s) covering %.1f km.",
                vehicle.getName(), rows.size(), totalKm);
    }

    private String etaGuidance(Long tenantId, Vehicle vehicle, Map<Long, String> vehicleNameMap, Map<String, Object> context,
            List<Citation> citations) {
        String base = vehicleDetail(tenantId, vehicle, ChatIntent.VEHICLE_STATUS, vehicleNameMap, context, citations);
        context.put("etaCapability", "ETA is calculated on the vehicle details and live tracking "
                + "screens. Choose a destination there to get a predicted arrival time.");
        return base + " To get a predicted arrival time, open the vehicle and use Predict ETA with "
                + "a destination.";
    }

    private String dispatchGuidance(Long tenantId, Map<Long, String> vehicleNameMap, Map<String, Object> context,
            List<Citation> citations) {
        String base = fleetStatus(tenantId, vehicleNameMap, context, citations);
        context.put("dispatchCapability", "Dispatch recommendations rank available vehicles for a "
                + "job. The assistant can only recommend; assigning a vehicle requires explicit "
                + "confirmation on the dispatch screen.");
        return base + " For a ranked recommendation, open Dispatch and enter the pickup location; "
                + "you must confirm the assignment yourself.";
    }

    private String reportSummary(AppUserPrincipal user, Long tenantId, Map<String, Object> context) {
        if (!user.hasPermission(PermissionKeys.VIEW_REPORTS)) {
            context.put("permissionDenied", "reports");
            return "You do not have permission to view reports.";
        }
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<TripFeatureSnapshot> trips = tripRepository
                .findByTenantId(tenantId, PageRequest.of(0, 200)).getContent();
        double distance = 0;
        int minutes = 0;
        int counted = 0;
        for (TripFeatureSnapshot trip : trips) {
            if (trip.getStartTime() != null && trip.getStartTime().isBefore(since)) {
                continue;
            }
            distance += trip.getDistanceKm();
            minutes += trip.getDurationMinutes();
            counted++;
        }
        context.put("weeklyReport", Map.of(
                "windowDays", 7,
                "trips", counted,
                "distanceKm", Math.round(distance * 10) / 10.0,
                "drivingMinutes", minutes));
        if (counted == 0) {
            return "No completed trips were recorded in the last 7 days, so there is nothing to "
                    + "summarise.";
        }
        return String.format("In the last 7 days your fleet completed %d trip(s) covering %.1f km "
                + "over %d minutes of driving.", counted, distance, minutes);
    }

    private String geofences(AppUserPrincipal user, Long tenantId, Map<String, Object> context) {
        if (!user.hasPermission(PermissionKeys.MANAGE_GEOFENCES)) {
            context.put("permissionDenied", "geofences");
            return "You do not have permission to view geofences.";
        }
        long total = geofenceRepository.countByTenantId(tenantId);
        int pending = geofenceSuggestionRepository.findByTenantIdAndStatus(tenantId, "PENDING").size();
        context.put("geofences", Map.of("total", total, "pendingSuggestions", pending));
        return String.format("You have %d geofence(s) and %d pending AI suggestion(s).", total, pending);
    }

    private String deviceHealth(Long tenantId, Map<String, Object> context, List<Citation> citations) {
        List<Device> devices = deviceRepository.findByTenantId(tenantId);
        Map<Long, DeviceCurrentPosition> byDevice = new LinkedHashMap<>();
        for (DeviceCurrentPosition position : currentPositionRepository.findByTenantId(tenantId)) {
            byDevice.put(position.getDeviceId(), position);
        }

        Instant staleBefore = Instant.now().minus(Duration.ofHours(2));
        List<Map<String, Object>> stale = new ArrayList<>();
        for (Device device : devices) {
            DeviceCurrentPosition position = byDevice.get(device.getId());
            Instant last = position == null ? null : position.getServerTime();
            if (last != null && last.isAfter(staleBefore)) {
                continue;
            }
            if (stale.size() < 15) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("device", device.getName());
                row.put("lastPacket", last == null ? "never" : last.toString());
                stale.add(row);
            }
            citations.add(new Citation("DEVICE", device.getId(), device.getName()));
        }
        context.put("deviceHealth", Map.of(
                "totalDevices", devices.size(),
                "reportingRecently", devices.size() - stale.size(),
                "notReporting", stale));

        if (stale.isEmpty()) {
            return String.format("All %d GPS device(s) have reported in the last 2 hours.", devices.size());
        }
        return String.format("%d of %d GPS device(s) have not reported in the last 2 hours.",
                stale.size(), devices.size());
    }

    private String unrecognised(Map<String, Object> context, String question) {
        context.put("unrecognisedQuestion", true);
        context.put("note", "The user's question was brief or poorly formatted. "
                + "Instead of rejecting it immediately, try to infer what they want and use your available tools to answer it (e.g. if they say 'Vehicle number', check the vehicle list). "
                + "CRITICAL RULE: Do NOT mention your tools, capabilities, system constraints, 'retrieve data', or the fact that you are an AI. "
                + "Do NOT list your available functions. Just provide a helpful answer if possible.");
        context.put("supportedTopics", SUPPORTED_TOPICS);
        return "I could not match that to something I can look up. I can help with fleet "
                + "status, a specific vehicle's location or status, recent alerts, maintenance, "
                + "driver safety scores, fuel, trip history, ETA, dispatch recommendations, "
                + "geofences, device health and reports.";
    }

    private static final List<String> SUPPORTED_TOPICS = List.of(
            "fleet status", "vehicle status", "current location", "recent alerts", "maintenance",
            "driver safety", "fuel", "trip history", "ETA", "dispatch", "geofences",
            "device health", "reports");

    private String appHelp(Map<String, Object> context) {
        context.put("appCapabilities", List.of(
                "Live map with real-time positions and trip playback",
                "Vehicles, devices, groups and projects",
                "Geofences with entry/exit alerts and AI suggestions",
                "Reports for trips, stops, distance and idling, with export",
                "AI command centre: alerts, driver scores, maintenance, ETA and dispatch",
                "Device commands, which require permission and explicit confirmation"));
        return "I can help with live tracking, alerts, reports, geofences, driver scores, "
                + "maintenance predictions, ETA and dispatch recommendations. Ask about any of "
                + "those, or tell me which vehicle you are interested in.";
    }

    private String users(Long tenantId, Map<String, Object> context) {
        List<com.glivt.user.User> users = userRepository.findByTenantId(tenantId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.glivt.user.User user : users.stream().limit(10).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", user.getName());
            row.put("username", user.getUsername());
            row.put("role", user.getRole().name());
            row.put("email", user.getEmail());
            rows.add(row);
        }
        context.put("users", rows);
        context.put("totalUsers", users.size());

        if (users.isEmpty()) {
            return "No users are registered in your tenant.";
        }
        return String.format("You have %d registered user(s). The first few are: %s.",
                users.size(), String.join(", ", users.stream().limit(5).map(com.glivt.user.User::getName).toList()));
    }

    private String projects(Long tenantId, Map<String, Object> context) {
        List<com.glivt.project.Project> projects = projectRepository.findByTenantId(tenantId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.glivt.project.Project p : projects.stream().limit(10).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getName());
            row.put("description", p.getDescription());
            rows.add(row);
        }
        context.put("projects", rows);
        context.put("totalProjects", projects.size());

        if (projects.isEmpty()) {
            return "No projects exist in your tenant.";
        }
        return String.format("You have %d project(s). The first few are: %s.",
                projects.size(), String.join(", ", projects.stream().limit(5).map(com.glivt.project.Project::getName).toList()));
    }

    private String commands(Long tenantId, Map<String, Object> context) {
        PageRequest page = PageRequest.of(0, 10);
        List<com.glivt.command.DeviceCommand> cmds = commandRepository.findByTenantIdOrderByRequestedAtDesc(tenantId, page).getContent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.glivt.command.DeviceCommand cmd : cmds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("commandType", cmd.getCommandType());
            row.put("status", cmd.getStatus().name());
            row.put("deviceId", cmd.getDeviceId());
            row.put("requestedAt", cmd.getRequestedAt() == null ? null : cmd.getRequestedAt().toString());
            rows.add(row);
        }
        context.put("recentCommands", rows);
        context.put("totalCommandsCount", commandRepository.countByTenantId(tenantId));

        if (cmds.isEmpty()) {
            return "No device commands have been executed in your tenant yet.";
        }
        return String.format("There are recent device commands. The latest command was %s (status: %s).",
                cmds.get(0).getCommandType(), cmds.get(0).getStatus().name());
    }

    private String tenants(Long tenantId, Map<String, Object> context) {
        Optional<com.glivt.tenant.Tenant> tenant = tenantRepository.findById(tenantId);
        if (tenant.isPresent()) {
            com.glivt.tenant.Tenant t = tenant.get();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.getName());
            row.put("companyCode", t.getCompanyCode());
            row.put("companyName", t.getCompanyName());
            row.put("appName", t.getAppName());
            row.put("status", t.getStatus().name());
            row.put("maxHistoryDays", t.getMaxHistoryDays());
            context.put("tenant", row);
            return String.format("You are logged in to organization: %s (Company Code: %s).", t.getName(), t.getCompanyCode());
        }
        return "Organization information is not available.";
    }


    private String searchAppKnowledge(Long tenantId, String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);

        // Step 1: Lexical (keyword) match
        AppKnowledgeDoc bestLexical = null;
        int maxMatches = 0;
        for (AppKnowledgeDoc doc : APP_KNOWLEDGE) {
            int matches = 0;
            for (String kw : doc.getKeywords()) {
                if (normalizedQuestion.contains(kw)) {
                    matches++;
                }
            }
            if (matches > maxMatches) {
                maxMatches = matches;
                bestLexical = doc;
            }
        }

        if (bestLexical != null && maxMatches > 0) {
            log.debug("App knowledge lexical match: {} (score={})", bestLexical.getId(), maxMatches);
            return bestLexical.getContent();
        }

        // Step 2: Semantic match via Python AI Service (embedding search)
        log.debug("No lexical match for app knowledge. Falling back to semantic search.");
        try {
            List<Map<String, Object>> documents = new ArrayList<>();
            for (AppKnowledgeDoc doc : APP_KNOWLEDGE) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("id", doc.getId());
                document.put("source_type", "APP_KNOWLEDGE");
                document.put("source_id", 0L);
                document.put("content", doc.getContent());
                document.put("metadata", Map.of("topic", doc.getTopic()));
                documents.add(document);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenant_id", tenantId);
            payload.put("query", question);
            payload.put("limit", 1);
            payload.put("min_score", 0.35);
            payload.put("documents", documents);

            // Use quick 2-second timeout so semantic search doesn't hang the chat flow
            AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/search/embeddings",
                    payload, new PythonAiClient.AiCallOptions("search.knowledge", tenantId, null, 2000));

            if (result.success()) {
                Map<String, Object> body = result.payload();
                if (body.get("matches") instanceof List<?> list && !list.isEmpty()) {
                    Object entry = list.get(0);
                    if (entry instanceof Map<?, ?> raw) {
                        return String.valueOf(raw.get("content"));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("App knowledge semantic search failed: {}", ex.getMessage());
        }

        return null;
    }

    private String vehicleLabel(Map<Long, String> vehicleNameMap, Long vehicleId) {
        if (vehicleId == null) {
            return "Unassigned";
        }
        String name = vehicleNameMap.get(vehicleId);
        return (name != null && !name.isBlank()) ? name : "Vehicle #" + vehicleId;
    }

    private String driverLabel(Map<Long, String> driverNameMap, Long driverId) {
        if (driverId == null) {
            return "Unassigned";
        }
        String name = driverNameMap.get(driverId);
        return (name != null && !name.isBlank()) ? name : "Driver #" + driverId;
    }

    private static final List<AppKnowledgeDoc> APP_KNOWLEDGE = List.of(
            new AppKnowledgeDoc("kb:vehicles", "vehicles", List.of("vehicle", "vehicles", "fleet", "car", "cars", "truck", "trucks", "list"),
                    "The Vehicles screen shows the list of all vehicles. You can search by vehicle name or registration number, and filter by status. Tapping on a vehicle opens its profile with live tracking, report shortcuts, and device commands."),
            new AppKnowledgeDoc("kb:tracking", "tracking", List.of("tracking", "track", "live", "location", "map", "position", "real-time"),
                    "The Fleet Map shows real-time GPS tracking for all active vehicles. Tapping a vehicle centers the map, shows its current speed, ignition status, and address, and links to the Trip Playback screen."),
            new AppKnowledgeDoc("kb:history", "history", List.of("history", "route", "playback", "past", "route history"),
                    "The Trip Playback screen allows you to play back past routes for a selected vehicle. It shows speed, status changes, route deviations, and total distance covered, with play/pause and speed controls."),
            new AppKnowledgeDoc("kb:drivers", "drivers", List.of("driver", "drivers", "operator", "score", "scores", "safety", "behavior", "behaviour"),
                    "The Drivers module under Management handles driver details, safety scores, compliance, and efficiency. Daily driver safety scores are calculated based on harsh braking, speeding, and route deviations."),
            new AppKnowledgeDoc("kb:devices", "gps devices", List.of("device", "devices", "gps", "tracker", "imei"),
                    "The GPS Devices module manages tracking hardware. Each device has a unique IMEI number, a SIM card number, and is assigned to one vehicle. Device health status is monitored in real-time."),
            new AppKnowledgeDoc("kb:users", "users", List.of("user", "users", "member", "members", "team", "role", "roles"),
                    "The Users module in the Management section allows Admins to view, create, edit, and disable user accounts, and assign roles such as Super Admin, Admin, and Driver."),
            new AppKnowledgeDoc("kb:geofences", "geofences", List.of("geofence", "geofences", "zone", "zones", "fence", "fences", "boundary", "boundaries", "create"),
                    "The Geofences screen lists active geofences. You can create a new circle geofence by defining a center point and a radius. Entry/exit events generate alerts. The AI engine can suggest optimized geofence zones based on frequent stops."),
            new AppKnowledgeDoc("kb:reports", "reports", List.of("report", "reports", "export", "exports", "csv"),
                    "The Reports screen allows users to generate and download CSV reports (trips, stops, distance, idling). Generated reports are saved to the device's document directory. Scheduled reports can be configured to run automatically."),
            new AppKnowledgeDoc("kb:commands", "commands", List.of("command", "commands", "cut", "immobilise", "immobilize", "lock", "unlock"),
                    "The Command Centre allows sending direct GPRS commands to tracking devices, such as cut engine (immobilise), restore engine, lock, and unlock. Destructive commands require explicit confirmation."),
            new AppKnowledgeDoc("kb:maintenance", "maintenance", List.of("maintenance", "maint", "service", "services", "repair", "repairs", "due"),
                    "The Maintenance screen displays predictive service recommendations (such as due for service, battery, odometer, engine hours, or breakdown risk). These are updated daily by the background maintenance prediction job."),
            new AppKnowledgeDoc("kb:alerts", "alerts", List.of("alert", "alerts", "event", "events", "warning", "warnings", "incident", "incidents", "notification", "notifications"),
                    "The Events screen shows system alerts and AI events (e.g. harsh braking, speeding, geofence entry/exit, route deviation). Admins can acknowledge alerts, which is recorded in the audit log."),
            new AppKnowledgeDoc("kb:tenants", "tenants", List.of("tenant", "tenants", "company", "organisation", "organization", "code"),
                    "Glivit is a multi-tenant platform. Tenant isolation is strictly enforced at the database level. Each organization has a unique Company Code, white-label branding, customized colors, logo, and enabled modules.")
    );

    public static class AppKnowledgeDoc {
        private final String id;
        private final String topic;
        private final List<String> keywords;
        private final String content;

        public AppKnowledgeDoc(String id, String topic, List<String> keywords, String content) {
            this.id = id;
            this.topic = topic;
            this.keywords = keywords;
            this.content = content;
        }

        public String getId() { return id; }
        public String getTopic() { return topic; }
        public List<String> getKeywords() { return keywords; }
        public String getContent() { return content; }
    }
}
