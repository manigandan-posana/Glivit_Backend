package com.glivt.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.entity.TenantSpeedPolicy;
import com.glivt.ai.repository.TenantSpeedPolicyRepository;
import com.glivt.geofence.Geofence;
import com.glivt.geofence.GeofenceRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleCategory;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the speed limit that applies to a vehicle at a position, entirely
 * server-side.
 *
 * <p>A speed limit reported by a GPS tracker is attacker- and misconfiguration-
 * controlled: a device that claims a 200 km/h limit would silently disable
 * speeding detection. It is therefore never used. The limit is resolved in this
 * order, and the winning source is always reported so it appears in the
 * anomaly evidence:
 *
 * <ol>
 *   <li>route-specific rule on the assigned route</li>
 *   <li>active geofence rule containing the position</li>
 *   <li>road metadata, where a provider supplies it</li>
 *   <li>tenant policy for the vehicle category, then the tenant default</li>
 *   <li>vehicle-type default</li>
 * </ol>
 */
@Service
public class SpeedLimitResolver {

    private static final Logger log = LoggerFactory.getLogger(SpeedLimitResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String SOURCE_ROUTE = "ROUTE_RULE";
    public static final String SOURCE_GEOFENCE = "GEOFENCE_RULE";
    public static final String SOURCE_ROAD = "ROAD_METADATA";
    public static final String SOURCE_TENANT = "TENANT_POLICY";
    public static final String SOURCE_TYPE_DEFAULT = "VEHICLE_TYPE_DEFAULT";

    /** Conservative per-category defaults used when nothing else is configured. */
    private static final Map<VehicleCategory, Double> TYPE_DEFAULTS = Map.of(
            VehicleCategory.CAR, 80.0,
            VehicleCategory.TRUCK, 60.0,
            VehicleCategory.BUS, 60.0,
            VehicleCategory.MIXER_TRUCK, 50.0,
            VehicleCategory.BIKE, 60.0,
            VehicleCategory.HEAVY_MACHINERY, 25.0,
            VehicleCategory.GPS_DEVICE, 80.0);

    private static final double FALLBACK_LIMIT_KPH = 60.0;

    private final TenantSpeedPolicyRepository policyRepository;
    private final GeofenceRepository geofenceRepository;

    public SpeedLimitResolver(TenantSpeedPolicyRepository policyRepository,
            GeofenceRepository geofenceRepository) {
        this.policyRepository = policyRepository;
        this.geofenceRepository = geofenceRepository;
    }

    public record ResolvedSpeedLimit(double limitKph, String source, String sourceReference) {
    }

    /**
     * @param routeSpeedLimitKph limit attached to the vehicle's assigned route, if any
     * @param roadSpeedLimitKph  limit from a road-metadata provider, if available
     */
    @Transactional(readOnly = true)
    public ResolvedSpeedLimit resolve(Long tenantId, Vehicle vehicle, double latitude,
            double longitude, Double routeSpeedLimitKph, String routeName,
            Double roadSpeedLimitKph) {

        if (isUsable(routeSpeedLimitKph)) {
            return new ResolvedSpeedLimit(routeSpeedLimitKph, SOURCE_ROUTE, routeName);
        }

        ResolvedSpeedLimit geofenceLimit = fromGeofence(tenantId, latitude, longitude);
        if (geofenceLimit != null) {
            return geofenceLimit;
        }

        if (isUsable(roadSpeedLimitKph)) {
            return new ResolvedSpeedLimit(roadSpeedLimitKph, SOURCE_ROAD, null);
        }

        ResolvedSpeedLimit tenantLimit = fromTenantPolicy(tenantId, vehicle);
        if (tenantLimit != null) {
            return tenantLimit;
        }

        VehicleCategory category = vehicle != null && vehicle.getCategory() != null
                ? vehicle.getCategory()
                : VehicleCategory.CAR;
        double limit = TYPE_DEFAULTS.getOrDefault(category, FALLBACK_LIMIT_KPH);
        return new ResolvedSpeedLimit(limit, SOURCE_TYPE_DEFAULT, category.name());
    }

    private ResolvedSpeedLimit fromGeofence(Long tenantId, double latitude, double longitude) {
        List<Geofence> geofences;
        try {
            geofences = geofenceRepository.findByTenantIdAndActiveTrue(tenantId);
        } catch (Exception ex) {
            log.debug("Could not load geofences for speed-limit resolution: {}", ex.getMessage());
            return null;
        }

        ResolvedSpeedLimit strictest = null;
        for (Geofence geofence : geofences) {
            Double limit = geofence.getSpeedLimitKph();
            if (!isUsable(limit) || !contains(geofence, latitude, longitude)) {
                continue;
            }
            // Overlapping zones: the strictest limit wins.
            if (strictest == null || limit < strictest.limitKph()) {
                strictest = new ResolvedSpeedLimit(limit, SOURCE_GEOFENCE, geofence.getName());
            }
        }
        return strictest;
    }

    private ResolvedSpeedLimit fromTenantPolicy(Long tenantId, Vehicle vehicle) {
        List<TenantSpeedPolicy> policies = policyRepository.findByTenantId(tenantId);
        if (policies.isEmpty()) {
            return null;
        }
        String category = vehicle != null && vehicle.getCategory() != null
                ? vehicle.getCategory().name()
                : null;

        TenantSpeedPolicy categoryMatch = null;
        TenantSpeedPolicy tenantDefault = null;
        for (TenantSpeedPolicy policy : policies) {
            if (policy.getVehicleCategory() == null || policy.getVehicleCategory().isBlank()) {
                tenantDefault = policy;
            } else if (policy.getVehicleCategory().equalsIgnoreCase(category)) {
                categoryMatch = policy;
            }
        }
        TenantSpeedPolicy chosen = categoryMatch != null ? categoryMatch : tenantDefault;
        if (chosen == null || !isUsable(chosen.getSpeedLimitKph())) {
            return null;
        }
        return new ResolvedSpeedLimit(chosen.getSpeedLimitKph(), SOURCE_TENANT,
                chosen.getVehicleCategory() == null ? "tenant default" : chosen.getVehicleCategory());
    }

    /** Point-in-geofence for the circle and polygon shapes the platform stores. */
    static boolean contains(Geofence geofence, double latitude, double longitude) {
        double[][] coordinates = parseCoordinates(geofence.getCoordinatesJson());
        if (coordinates.length == 0) {
            return false;
        }
        if ("CIRCLE".equalsIgnoreCase(geofence.getType())) {
            Double radius = geofence.getRadiusMeters();
            if (radius == null || radius <= 0) {
                return false;
            }
            double distance = GeoMath.haversineMeters(latitude, longitude,
                    coordinates[0][0], coordinates[0][1]);
            return distance <= radius;
        }
        return pointInPolygon(latitude, longitude, coordinates);
    }

    /**
     * Geofence coordinates are stored GeoJSON-style as {@code [[lng,lat],...]}.
     * Returned here normalised to {@code [lat,lng]}.
     */
    static double[][] parseCoordinates(String json) {
        if (json == null || json.isBlank()) {
            return new double[0][];
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                return new double[0][];
            }
            List<double[]> points = new java.util.ArrayList<>();
            for (JsonNode entry : node) {
                if (!entry.isArray() || entry.size() < 2) {
                    continue;
                }
                double lng = entry.get(0).asDouble(Double.NaN);
                double lat = entry.get(1).asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) {
                    continue;
                }
                points.add(new double[] {lat, lng});
            }
            return points.toArray(new double[0][]);
        } catch (Exception ex) {
            return new double[0][];
        }
    }

    private static boolean pointInPolygon(double lat, double lng, double[][] polygon) {
        if (polygon.length < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            double yi = polygon[i][0];
            double xi = polygon[i][1];
            double yj = polygon[j][0];
            double xj = polygon[j][1];
            boolean straddles = (yi > lat) != (yj > lat);
            if (straddles && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean isUsable(Double limit) {
        return limit != null && limit > 0 && limit < 300;
    }
}
