package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.glivt.ai.service.GeoMath;
import com.glivt.ai.service.RouteDeviationService;
import com.glivt.route.VehicleRoute;
import com.glivt.route.VehicleRouteAssignment;
import com.glivt.route.VehicleRouteAssignmentRepository;
import com.glivt.route.VehicleRouteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Route deviation must be a real measured distance to the assigned corridor -
 * the previous pipeline hard-coded it to 0.0, which made deviation detection
 * impossible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteDeviationServiceTest {

    @Mock private VehicleRouteAssignmentRepository assignmentRepository;
    @Mock private VehicleRouteRepository routeRepository;

    private RouteDeviationService service;

    private static final Long TENANT = 1L;
    private static final Long VEHICLE = 100L;

    @BeforeEach
    void setUp() {
        service = new RouteDeviationService(assignmentRepository, routeRepository);
    }

    /** A straight east-west corridor along latitude 12.9700. */
    private VehicleRoute route(double allowedDeviation) {
        VehicleRoute route = new VehicleRoute();
        route.setId(42L);
        route.setTenantId(TENANT);
        route.setName("Depot to Port");
        route.setActive(true);
        route.setAllowedDeviationMeters(allowedDeviation);
        route.setPathJson("[[12.9700,77.5900],[12.9700,77.6000],[12.9700,77.6100]]");
        return route;
    }

    private void assign(VehicleRoute route) {
        VehicleRouteAssignment assignment = new VehicleRouteAssignment();
        assignment.setId(7L);
        assignment.setTenantId(TENANT);
        assignment.setRouteId(route.getId());
        assignment.setVehicleId(VEHICLE);
        assignment.setActive(true);
        assignment.setStartTime(Instant.now().minusSeconds(3600));
        when(assignmentRepository.findActiveAt(eq(TENANT), eq(VEHICLE), any()))
                .thenReturn(List.of(assignment));
        when(routeRepository.findByIdAndTenantId(route.getId(), TENANT))
                .thenReturn(Optional.of(route));
    }

    @Test
    void returnsEmptyWhenNoRouteIsAssigned() {
        when(assignmentRepository.findActiveAt(eq(TENANT), eq(VEHICLE), any())).thenReturn(List.of());

        // "No route" must be distinguishable from "zero deviation".
        assertThat(service.resolve(TENANT, VEHICLE, 12.97, 77.59, Instant.now())).isEmpty();
    }

    @Test
    void measuresRealPerpendicularDistanceToThePolyline() {
        assign(route(500));

        // 0.0090 degrees of latitude north of the corridor is roughly 1000 m.
        var deviation = service.resolve(TENANT, VEHICLE, 12.9790, 77.5950, Instant.now())
                .orElseThrow();

        assertThat(deviation.routeId()).isEqualTo(42L);
        assertThat(deviation.distanceMeters()).isBetween(950.0, 1050.0);
        assertThat(deviation.insideCorridor()).isFalse();
        assertThat(deviation.allowedDeviation()).isEqualTo(500.0);
        // The nearest point sits on the corridor, at the vehicle's longitude.
        assertThat(deviation.nearestLatitude()).isCloseTo(12.9700, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(deviation.nearestLongitude()).isCloseTo(77.5950, org.assertj.core.data.Offset.offset(1e-3));
        // A re-entry point is offered so the driver can be guided back.
        assertThat(deviation.reentryLatitude()).isNotNull();
    }

    @Test
    void vehicleOnTheRouteIsInsideTheCorridor() {
        assign(route(500));

        var deviation = service.resolve(TENANT, VEHICLE, 12.9701, 77.5950, Instant.now())
                .orElseThrow();

        assertThat(deviation.distanceMeters()).isLessThan(50.0);
        assertThat(deviation.insideCorridor()).isTrue();
        assertThat(deviation.reentryLatitude()).isNull();
    }

    @Test
    void allowedDeviationComesFromTheRoute() {
        assign(route(2000));

        var deviation = service.resolve(TENANT, VEHICLE, 12.9790, 77.5950, Instant.now())
                .orElseThrow();

        // Same 1000 m offset, but this corridor tolerates 2 km.
        assertThat(deviation.distanceMeters()).isBetween(950.0, 1050.0);
        assertThat(deviation.insideCorridor()).isTrue();
    }

    @Test
    void anotherTenantsRouteIsNeverResolved() {
        VehicleRoute route = route(500);
        VehicleRouteAssignment assignment = new VehicleRouteAssignment();
        assignment.setTenantId(TENANT);
        assignment.setRouteId(route.getId());
        assignment.setVehicleId(VEHICLE);
        assignment.setActive(true);
        when(assignmentRepository.findActiveAt(eq(TENANT), eq(VEHICLE), any()))
                .thenReturn(List.of(assignment));
        // Tenant-scoped lookup misses, as it would for a foreign route.
        when(routeRepository.findByIdAndTenantId(route.getId(), TENANT)).thenReturn(Optional.empty());

        assertThat(service.resolve(TENANT, VEHICLE, 12.9790, 77.5950, Instant.now())).isEmpty();
    }

    @Test
    void invalidPathGeometryIsIgnoredRatherThanReportedAsZeroDeviation() {
        VehicleRoute route = route(500);
        route.setPathJson("[[999,999],[\"bad\",\"data\"]]");
        assign(route);

        assertThat(service.resolve(TENANT, VEHICLE, 12.9790, 77.5950, Instant.now())).isEmpty();
    }

    @Test
    void polylineProjectionClampsToSegmentEnds() {
        double[][] path = {{12.97, 77.59}, {12.97, 77.60}};
        // Well past the eastern end of the segment.
        GeoMath.Projection projection = GeoMath.distanceToPolyline(12.97, 77.62, path);

        assertThat(projection).isNotNull();
        assertThat(projection.longitude()).isCloseTo(77.60, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(projection.distanceMeters()).isGreaterThan(1000.0);
    }
}
