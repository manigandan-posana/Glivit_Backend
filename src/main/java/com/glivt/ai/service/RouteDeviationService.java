package com.glivt.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.route.VehicleRoute;
import com.glivt.route.VehicleRouteAssignment;
import com.glivt.route.VehicleRouteAssignmentRepository;
import com.glivt.route.VehicleRouteRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the route a vehicle is assigned to and measures the real deviation
 * from it.
 *
 * <p>This replaces the hard-coded {@code distance_from_route_meters = 0.0} that
 * made route-deviation detection impossible: the corridor is now resolved from
 * tenant-scoped data and the distance is the shortest distance from the current
 * coordinate to the route polyline.
 */
@Service
public class RouteDeviationService {

    private static final Logger log = LoggerFactory.getLogger(RouteDeviationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VehicleRouteAssignmentRepository assignmentRepository;
    private final VehicleRouteRepository routeRepository;

    public RouteDeviationService(VehicleRouteAssignmentRepository assignmentRepository,
            VehicleRouteRepository routeRepository) {
        this.assignmentRepository = assignmentRepository;
        this.routeRepository = routeRepository;
    }

    /**
     * Deviation of a vehicle from its assigned route.
     *
     * @param routeId              the resolved route, null when none is assigned
     * @param distanceMeters       shortest distance to the route polyline
     * @param allowedDeviation     the corridor half-width for this route
     * @param insideCorridor       whether the vehicle is still within the corridor
     * @param nearestLatitude      closest point on the route
     * @param reentryLatitude      suggested point to rejoin the route, may be null
     */
    public record RouteDeviation(
            Long routeId,
            String routeName,
            Double routeSpeedLimitKph,
            double distanceMeters,
            double allowedDeviation,
            boolean insideCorridor,
            double nearestLatitude,
            double nearestLongitude,
            Double reentryLatitude,
            Double reentryLongitude) {
    }

    /**
     * Resolve the active route for a vehicle at a point in time and measure the
     * deviation. Every lookup is tenant-scoped.
     *
     * @return empty when the vehicle has no active route (which is normal for
     *         ad-hoc fleets) - callers must then report "route not resolved"
     *         rather than a deviation of zero.
     */
    @Transactional(readOnly = true)
    public Optional<RouteDeviation> resolve(Long tenantId, Long vehicleId, double latitude,
            double longitude, Instant at) {
        if (tenantId == null || vehicleId == null) {
            return Optional.empty();
        }
        List<VehicleRouteAssignment> assignments =
                assignmentRepository.findActiveAt(tenantId, vehicleId, at == null ? Instant.now() : at);
        if (assignments.isEmpty()) {
            return Optional.empty();
        }
        VehicleRouteAssignment assignment = assignments.get(0);

        VehicleRoute route = routeRepository
                .findByIdAndTenantId(assignment.getRouteId(), tenantId)
                .orElse(null);
        if (route == null || !route.isActive()) {
            return Optional.empty();
        }

        double[][] path = parsePath(route);
        if (path.length == 0) {
            log.warn("Route {} for tenant {} has no usable path geometry", route.getId(), tenantId);
            return Optional.empty();
        }

        GeoMath.Projection projection = GeoMath.distanceToPolyline(latitude, longitude, path);
        if (projection == null) {
            return Optional.empty();
        }

        double allowed = route.getAllowedDeviationMeters() > 0
                ? route.getAllowedDeviationMeters()
                : 500.0;
        boolean inside = projection.distanceMeters() <= allowed;

        Double reentryLat = null;
        Double reentryLng = null;
        if (!inside) {
            double[] reentry = GeoMath.reentryPoint(path, projection.segmentIndex());
            if (reentry != null) {
                reentryLat = reentry[0];
                reentryLng = reentry[1];
            }
        }

        return Optional.of(new RouteDeviation(
                route.getId(),
                route.getName(),
                route.getSpeedLimitKph(),
                projection.distanceMeters(),
                allowed,
                inside,
                projection.latitude(),
                projection.longitude(),
                reentryLat,
                reentryLng));
    }

    /** Parses {@code [[lat,lng],...]} into a dense array, skipping bad points. */
    static double[][] parsePath(VehicleRoute route) {
        String json = route.getPathJson();
        if (json == null || json.isBlank()) {
            return new double[0][];
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                return new double[0][];
            }
            List<double[]> points = new ArrayList<>();
            for (JsonNode entry : node) {
                double lat;
                double lng;
                if (entry.isArray() && entry.size() >= 2) {
                    lat = entry.get(0).asDouble(Double.NaN);
                    lng = entry.get(1).asDouble(Double.NaN);
                } else if (entry.isObject()) {
                    lat = entry.path("lat").asDouble(entry.path("latitude").asDouble(Double.NaN));
                    lng = entry.path("lng").asDouble(entry.path("longitude").asDouble(Double.NaN));
                } else {
                    continue;
                }
                if (Double.isNaN(lat) || Double.isNaN(lng)
                        || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    continue;
                }
                points.add(new double[] {lat, lng});
            }
            return points.toArray(new double[0][]);
        } catch (Exception ex) {
            log.warn("Route {} has invalid path JSON: {}", route.getId(), ex.getMessage());
            return new double[0][];
        } 
    }
}
