package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.DriverScoreDailyRepository;
import com.glivt.ai.repository.GeofenceSuggestionRepository;
import com.glivt.ai.repository.MaintenancePredictionRepository;
import com.glivt.ai.repository.TripFeatureSnapshotRepository;
import com.glivt.ai.service.ChatIntent;
import com.glivt.ai.service.FleetContextBuilder;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.DriverRepository;
import com.glivt.geofence.GeofenceRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.Permissions;
import com.glivt.user.Role;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import com.glivt.user.UserRepository;
import com.glivt.project.ProjectRepository;
import com.glivt.command.CommandRepository;
import com.glivt.tenant.TenantRepository;
import com.glivt.ai.client.PythonAiClient;
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
 * Context selection by intent.
 *
 * <p>The regression that motivated this: asking "Tenant list" - which is not a
 * fleet topic at all - returned a confident vehicle count, because an
 * unrecognised intent silently fell through to fleet status. Answering a
 * question the user did not ask is worse than admitting the question was not
 * understood.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FleetContextBuilderTest {

    private static final Long TENANT = 1L;

    @Mock private VehicleRepository vehicleRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DriverRepository driverRepository;
    @Mock private DeviceCurrentPositionRepository currentPositionRepository;
    @Mock private AiEventRepository aiEventRepository;
    @Mock private MaintenancePredictionRepository maintenanceRepository;
    @Mock private DriverScoreDailyRepository driverScoreRepository;
    @Mock private TripFeatureSnapshotRepository tripRepository;
    @Mock private GeofenceRepository geofenceRepository;
    @Mock private GeofenceSuggestionRepository geofenceSuggestionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CommandRepository commandRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PythonAiClient pythonAiClient;

    private FleetContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FleetContextBuilder(vehicleRepository, deviceRepository, driverRepository,
                currentPositionRepository, aiEventRepository, maintenanceRepository,
                driverScoreRepository, tripRepository, geofenceRepository,
                geofenceSuggestionRepository, userRepository, projectRepository,
                commandRepository, tenantRepository, pythonAiClient);

        DeviceCurrentPosition running = new DeviceCurrentPosition();
        running.setDeviceId(1L);
        running.setTenantId(TENANT);
        running.setVehicleId(10L);
        running.setState(DeviceState.RUNNING);
        running.setSpeed(42);

        when(currentPositionRepository.findByTenantId(TENANT)).thenReturn(List.of(running));
        when(vehicleRepository.countByTenantId(TENANT)).thenReturn(4L);
        when(aiEventRepository.countByTenantIdAndAcknowledgedFalse(TENANT)).thenReturn(0L);

        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setTenantId(TENANT);
        vehicle.setName("TN20CM7677");
        when(vehicleRepository.findByIdAndTenantId(10L, TENANT)).thenReturn(Optional.of(vehicle));
    }

    private AppUserPrincipal user() {
        return new AppUserPrincipal(9L, TENANT, TENANT, "operator", Role.ADMIN, true,
                Permissions.forUser(Role.ADMIN, null));
    }

    @Test
    void unrecognisedQuestionDoesNotReturnFleetStatus() {
        FleetContextBuilder.FleetContext context =
                builder.build(user(), ChatIntent.UNKNOWN, null, "Tenant list");

        // The deterministic answer must say it cannot help, not report vehicles.
        assertThat(context.deterministicAnswer()).contains("could not match that");
        assertThat(context.deterministicAnswer()).doesNotContain("4 vehicle");
        assertThat(context.context()).containsEntry("unrecognisedQuestion", true);
        // No fleet data is retrieved at all, so the model cannot invent from it.
        assertThat(context.context()).doesNotContainKey("fleetTotals");
        assertThat(context.context()).doesNotContainKey("vehicles");
        assertThat(context.citations()).isEmpty();
        verify(currentPositionRepository, never()).findByTenantId(anyLong());
    }

    @Test
    void unrecognisedQuestionListsWhatTheAssistantCanDo() {
        FleetContextBuilder.FleetContext context =
                builder.build(user(), ChatIntent.UNKNOWN, null, "Tenant list");

        assertThat(context.context()).containsKey("supportedTopics");
        assertThat(context.deterministicAnswer()).contains("fleet status");
        assertThat(context.deterministicAnswer()).contains("maintenance");
    }

    @Test
    void fleetStatusStillReportsRealCounts() {
        FleetContextBuilder.FleetContext context =
                builder.build(user(), ChatIntent.FLEET_STATUS, null, "how many vehicles are running?");

        assertThat(context.deterministicAnswer()).contains("4 vehicle");
        assertThat(context.deterministicAnswer()).contains("1 running");
        assertThat(context.context()).containsKey("fleetTotals");
        assertThat(context.citations()).isNotEmpty();
    }

    @Test
    void recentAlertsWithNoEventsSaysSoRatherThanInventing() {
        when(aiEventRepository.findTop10ByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of());

        FleetContextBuilder.FleetContext context =
                builder.build(user(), ChatIntent.RECENT_ALERTS, null, "any alerts?");

        assertThat(context.deterministicAnswer()).contains("no AI alerts");
        assertThat(context.context()).containsKey("recentAlerts");
    }

    @Test
    void locationQuestionWithoutASelectedVehicleAsksForOne() {
        FleetContextBuilder.FleetContext context =
                builder.build(user(), ChatIntent.CURRENT_LOCATION, null, "where is it?");

        assertThat(context.deterministicAnswer()).contains("No vehicle is selected");
        assertThat(context.context()).containsEntry("selectedVehicle", null);
    }
}
