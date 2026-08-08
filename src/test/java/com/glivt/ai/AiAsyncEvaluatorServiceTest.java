package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glivt.ai.client.AiErrorCode;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.service.AiAlertBroadcaster;
import com.glivt.ai.service.AiAsyncEvaluatorService;
import com.glivt.ai.service.AiEvaluationPayload;
import com.glivt.ai.service.AiGovernanceService;
import com.glivt.ai.service.AiIncidentService;
import com.glivt.ai.service.DriverAssignmentResolver;
import com.glivt.ai.service.RouteDeviationService;
import com.glivt.ai.service.SpeedLimitResolver;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleCategory;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The evaluator resolves route, speed limit and driver from tenant-scoped data
 * before scoring, and persists every meaningful anomaly from one packet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiAsyncEvaluatorServiceTest {

    private static final Long TENANT = 1L;
    private static final Long VEHICLE = 100L;
    private static final Long DEVICE = 10L;

    @Mock private PythonAiClient pythonAiClient;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private RouteDeviationService routeDeviationService;
    @Mock private SpeedLimitResolver speedLimitResolver;
    @Mock private DriverAssignmentResolver driverAssignmentResolver;
    @Mock private AiIncidentService incidentService;
    @Mock private AiAlertBroadcaster broadcaster;
    @Mock private AiGovernanceService governanceService;

    private AiAsyncEvaluatorService evaluator;
    private final List<AiEvent> recorded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        evaluator = new AiAsyncEvaluatorService(pythonAiClient, vehicleRepository,
                routeDeviationService, speedLimitResolver, driverAssignmentResolver,
                incidentService, broadcaster, governanceService);
        recorded.clear();

        Vehicle vehicle = new Vehicle();
        vehicle.setId(VEHICLE);
        vehicle.setTenantId(TENANT);
        vehicle.setName("TN20CM7677");
        vehicle.setCategory(VehicleCategory.TRUCK);
        when(vehicleRepository.findByIdAndTenantId(VEHICLE, TENANT)).thenReturn(Optional.of(vehicle));

        when(speedLimitResolver.resolve(anyLong(), any(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new SpeedLimitResolver.ResolvedSpeedLimit(60.0,
                        SpeedLimitResolver.SOURCE_ROUTE, "Route 7"));
        when(routeDeviationService.resolve(anyLong(), anyLong(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.empty());
        when(driverAssignmentResolver.resolve(anyLong(), anyLong(), any()))
                .thenReturn(Optional.of(new DriverAssignmentResolver.ResolvedDriver(55L, 9L, "ASSIGNMENT")));

        // Capture whatever the evaluator tries to persist.
        when(incidentService.record(any(AiEvent.class), any())).thenAnswer(invocation -> {
            AiEvent event = invocation.getArgument(0);
            recorded.add(event);
            return Optional.of(new AiIncidentService.IncidentOutcome(event, true, false));
        });
    }

    private AiEvaluationPayload payload() {
        return new AiEvaluationPayload(TENANT, DEVICE, VEHICLE, 999L,
                12.9716, 77.5946, 118.0, 117.0, -6.2, 5.0, 12.0, 10.0, 5.0, 1.0,
                0.0, true, Instant.parse("2026-07-01T10:00:00Z"));
    }

    private void stubAiResponse(Map<String, Object> body) {
        when(pythonAiClient.postForMap(eq("/v1/anomaly/score"), any(), any()))
                .thenReturn(AiResult.ok(body, 25L));
    }

    private Map<String, Object> event(String type, String severity, double score) {
        return Map.of("type", type, "severity", severity, "score", score,
                "explanation", type + " explanation", "evidence", Map.of("k", "v"));
    }

    @Test
    void persistsEveryMeaningfulAnomalyFromOnePacket() {
        stubAiResponse(Map.of(
                "anomaly_score", 0.95,
                "severity", "CRITICAL",
                "rule_version", "anomaly-rules-2.0",
                "source", "RULE",
                "events", List.of(
                        event("GPS_SPOOFING", "CRITICAL", 0.75),
                        event("SPEEDING", "HIGH", 0.6),
                        event("HARSH_BRAKING", "MEDIUM", 0.41))));

        int persisted = evaluator.evaluate(payload());

        assertThat(persisted).isEqualTo(3);
        assertThat(recorded).extracting(AiEvent::getEventType)
                .containsExactly("GPS_SPOOFING", "SPEEDING", "HARSH_BRAKING");
        // Each stored incident references the others seen on the same packet.
        ArgumentCaptor<Set<String>> related = ArgumentCaptor.captor();
        verify(incidentService, org.mockito.Mockito.times(3))
                .record(any(AiEvent.class), related.capture());
        assertThat(related.getAllValues().get(0)).containsExactlyInAnyOrder("SPEEDING", "HARSH_BRAKING");
    }

    @Test
    void discardsLowScoringNoise() {
        stubAiResponse(Map.of("events", List.of(
                event("ABNORMAL_GPS_ACCURACY", "LOW", 0.1),
                event("SPEEDING", "HIGH", 0.6))));

        int persisted = evaluator.evaluate(payload());

        assertThat(persisted).isEqualTo(1);
        assertThat(recorded).extracting(AiEvent::getEventType).containsExactly("SPEEDING");
    }

    @Test
    void attachesResolvedDriverAndAssignmentToEveryIncident() {
        stubAiResponse(Map.of("events", List.of(event("SPEEDING", "HIGH", 0.6))));

        evaluator.evaluate(payload());

        assertThat(recorded).hasSize(1);
        AiEvent stored = recorded.get(0);
        assertThat(stored.getDriverId()).isEqualTo(55L);
        assertThat(stored.getAssignmentId()).isEqualTo(9L);
        assertThat(stored.getVehicleId()).isEqualTo(VEHICLE);
        assertThat(stored.getDeviceId()).isEqualTo(DEVICE);
        assertThat(stored.getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void attachesTheServerResolvedSpeedLimitAndItsSource() {
        stubAiResponse(Map.of("events", List.of(event("SPEEDING", "HIGH", 0.6))));

        evaluator.evaluate(payload());

        AiEvent stored = recorded.get(0);
        assertThat(stored.getSpeedLimitKph()).isEqualTo(60.0);
        assertThat(stored.getSpeedLimitSource()).isEqualTo(SpeedLimitResolver.SOURCE_ROUTE);
    }

    @Test
    void sendsResolvedRouteContextAndNeverADeviceSuppliedLimit() {
        when(routeDeviationService.resolve(anyLong(), anyLong(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.of(new RouteDeviationService.RouteDeviation(
                        42L, "Depot to Port", 45.0, 1800.0, 500.0, false,
                        12.9700, 77.5950, 12.9700, 77.6000)));
        stubAiResponse(Map.of("events", List.of(event("ROUTE_DEVIATION", "HIGH", 0.6)),
                "deviation_path", List.of(Map.of("latitude", 12.97, "longitude", 77.59)),
                "reentry_point", Map.of("latitude", 12.97, "longitude", 77.60)));

        evaluator.evaluate(payload());

        ArgumentCaptor<Object> request = ArgumentCaptor.captor();
        verify(pythonAiClient).postForMap(eq("/v1/anomaly/score"), request.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) request.getValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) sent.get("route");
        assertThat(route).containsEntry("route_id", 42L);
        assertThat(route).containsEntry("distance_from_route_meters", 1800.0);
        assertThat(route).containsEntry("allowed_deviation_meters", 500.0);
        assertThat(route).containsEntry("inside_corridor", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) sent.get("speed_limit");
        assertThat(limit).containsEntry("limit_kph", 60.0);
        assertThat(limit).containsEntry("source", SpeedLimitResolver.SOURCE_ROUTE);

        // The time gap sent is always strictly positive - never a fabricated 0.5s.
        assertThat((Double) sent.get("time_gap_seconds")).isGreaterThan(0.0);

        AiEvent stored = recorded.get(0);
        assertThat(stored.getRouteId()).isEqualTo(42L);
        assertThat(stored.getDistanceFromRouteMeters()).isEqualTo(1800.0);
        assertThat(stored.getDeviationPathJson()).isNotNull();
        assertThat(stored.getReentryPointJson()).isNotNull();
    }

    @Test
    void aiOutageSkipsScoringWithoutThrowing() {
        when(pythonAiClient.postForMap(eq("/v1/anomaly/score"), any(), any()))
                .thenReturn(AiResult.failure(AiErrorCode.CONNECTION_REFUSED, "down", 12L));

        int persisted = evaluator.evaluate(payload());

        assertThat(persisted).isZero();
        verify(incidentService, never()).record(any(), any());
        // The outage is still recorded for governance and diagnostics.
        verify(governanceService).record(eq(TENANT), eq("anomaly.score"), eq("RULE"), any(), any(),
                any(), anyLong(), eq("CONNECTION_REFUSED"), eq("POSITION"), eq(999L));
    }

    @Test
    void broadcastsOnlyNewOrEscalatedIncidents() {
        when(incidentService.record(any(AiEvent.class), any())).thenAnswer(invocation -> {
            AiEvent event = invocation.getArgument(0);
            // Repeat observation: neither new nor escalated.
            return Optional.of(new AiIncidentService.IncidentOutcome(event, false, false));
        });
        stubAiResponse(Map.of("events", List.of(event("SPEEDING", "HIGH", 0.6))));

        evaluator.evaluate(payload());

        verify(broadcaster, never()).broadcast(anyLong(), any());
    }

    @Test
    void emptyEventListPersistsNothing() {
        stubAiResponse(Map.of("events", List.of(), "anomaly_score", 0.0));

        assertThat(evaluator.evaluate(payload())).isZero();
        verify(incidentService, never()).record(any(), any());
    }
}
