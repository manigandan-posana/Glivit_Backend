package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.glivt.ai.entity.TenantSpeedPolicy;
import com.glivt.ai.repository.TenantSpeedPolicyRepository;
import com.glivt.ai.service.SpeedLimitResolver;
import com.glivt.geofence.Geofence;
import com.glivt.geofence.GeofenceRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleCategory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * A speed limit reported by a GPS device is never trusted. These tests pin the
 * server-side resolution order and prove the winning source is always reported.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpeedLimitResolverTest {

    private static final Long TENANT = 1L;
    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;

    @Mock private TenantSpeedPolicyRepository policyRepository;
    @Mock private GeofenceRepository geofenceRepository;

    private SpeedLimitResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SpeedLimitResolver(policyRepository, geofenceRepository);
        when(policyRepository.findByTenantId(TENANT)).thenReturn(List.of());
        when(geofenceRepository.findByTenantIdAndActiveTrue(TENANT)).thenReturn(List.of());
    }

    private Vehicle vehicle(VehicleCategory category) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(100L);
        vehicle.setTenantId(TENANT);
        vehicle.setName("Test Vehicle");
        vehicle.setCategory(category);
        return vehicle;
    }

    private Geofence circle(String name, double lat, double lng, double radius, Double limit) {
        Geofence geofence = new Geofence();
        geofence.setId(1L);
        geofence.setTenantId(TENANT);
        geofence.setName(name);
        geofence.setType("CIRCLE");
        // Stored GeoJSON-style as [lng, lat].
        geofence.setCoordinatesJson("[[" + lng + "," + lat + "]]");
        geofence.setRadiusMeters(radius);
        geofence.setSpeedLimitKph(limit);
        geofence.setActive(true);
        return geofence;
    }

    @Test
    void routeRuleHasHighestPriority() {
        when(geofenceRepository.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(circle("Depot", LAT, LNG, 500, 20.0)));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.TRUCK), LAT, LNG,
                45.0, "Route 7", 90.0);

        assertThat(resolved.limitKph()).isEqualTo(45.0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_ROUTE);
        assertThat(resolved.sourceReference()).isEqualTo("Route 7");
    }

    @Test
    void geofenceRuleBeatsRoadMetadataAndTenantPolicy() {
        when(geofenceRepository.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(circle("School Zone", LAT, LNG, 500, 30.0)));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.CAR), LAT, LNG,
                null, null, 80.0);

        assertThat(resolved.limitKph()).isEqualTo(30.0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_GEOFENCE);
        assertThat(resolved.sourceReference()).isEqualTo("School Zone");
    }

    @Test
    void strictestGeofenceWinsWhenZonesOverlap() {
        when(geofenceRepository.findByTenantIdAndActiveTrue(TENANT)).thenReturn(List.of(
                circle("Yard", LAT, LNG, 500, 40.0),
                circle("Loading Bay", LAT, LNG, 500, 15.0)));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.TRUCK), LAT, LNG,
                null, null, null);

        assertThat(resolved.limitKph()).isEqualTo(15.0);
    }

    @Test
    void geofenceOutsideTheVehiclePositionIsIgnored() {
        // A 100 m zone roughly 5 km away.
        when(geofenceRepository.findByTenantIdAndActiveTrue(TENANT))
                .thenReturn(List.of(circle("Far Depot", 13.05, 77.65, 100, 10.0)));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.CAR), LAT, LNG,
                null, null, null);

        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_TYPE_DEFAULT);
    }

    @Test
    void roadMetadataBeatsTenantPolicy() {
        TenantSpeedPolicy policy = new TenantSpeedPolicy();
        policy.setTenantId(TENANT);
        policy.setSpeedLimitKph(70.0);
        when(policyRepository.findByTenantId(TENANT)).thenReturn(List.of(policy));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.CAR), LAT, LNG,
                null, null, 55.0);

        assertThat(resolved.limitKph()).isEqualTo(55.0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_ROAD);
    }

    @Test
    void categoryPolicyOverridesTenantDefault() {
        TenantSpeedPolicy tenantDefault = new TenantSpeedPolicy();
        tenantDefault.setTenantId(TENANT);
        tenantDefault.setSpeedLimitKph(70.0);

        TenantSpeedPolicy truckPolicy = new TenantSpeedPolicy();
        truckPolicy.setTenantId(TENANT);
        truckPolicy.setVehicleCategory("TRUCK");
        truckPolicy.setSpeedLimitKph(50.0);

        when(policyRepository.findByTenantId(TENANT))
                .thenReturn(List.of(tenantDefault, truckPolicy));

        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.TRUCK), LAT, LNG,
                null, null, null);

        assertThat(resolved.limitKph()).isEqualTo(50.0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_TENANT);
        assertThat(resolved.sourceReference()).isEqualTo("TRUCK");
    }

    @Test
    void fallsBackToVehicleTypeDefault() {
        var truck = resolver.resolve(TENANT, vehicle(VehicleCategory.TRUCK), LAT, LNG, null, null, null);
        var car = resolver.resolve(TENANT, vehicle(VehicleCategory.CAR), LAT, LNG, null, null, null);
        var machinery = resolver.resolve(TENANT, vehicle(VehicleCategory.HEAVY_MACHINERY), LAT, LNG,
                null, null, null);

        assertThat(truck.limitKph()).isEqualTo(60.0);
        assertThat(car.limitKph()).isEqualTo(80.0);
        assertThat(machinery.limitKph()).isEqualTo(25.0);
        assertThat(car.source()).isEqualTo(SpeedLimitResolver.SOURCE_TYPE_DEFAULT);
    }

    @Test
    void implausibleLimitsAreRejectedAtEveryLevel() {
        // A route or road source claiming 500 km/h is discarded, not used to
        // silently disable speeding detection.
        var resolved = resolver.resolve(TENANT, vehicle(VehicleCategory.CAR), LAT, LNG,
                500.0, "Bad Route", -10.0);

        assertThat(resolved.limitKph()).isEqualTo(80.0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_TYPE_DEFAULT);
    }

    @Test
    void handlesMissingVehicleGracefully() {
        var resolved = resolver.resolve(TENANT, null, LAT, LNG, null, null, null);

        assertThat(resolved.limitKph()).isGreaterThan(0);
        assertThat(resolved.source()).isEqualTo(SpeedLimitResolver.SOURCE_TYPE_DEFAULT);
    }
}
