package com.glivt.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glivt.ai.service.AiEvaluationPayload;
import com.glivt.ai.service.AiPositionOutboxService;
import com.glivt.ai.service.DeviceMotionStateService;
import com.glivt.ai.service.GpsFeatureService;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionIngestServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private PositionRepository positionRepository;
    @Mock private DeviceCurrentPositionRepository currentPositionRepository;
    @Mock private DeviceMotionStateService motionStateService;
    @Mock private AiPositionOutboxService outboxService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PositionIngestService service;

    @BeforeEach
    void setUp() {
        service = new PositionIngestService(deviceRepository, positionRepository,
                currentPositionRepository, new GpsFeatureService(), motionStateService,
                outboxService, eventPublisher);
        when(motionStateService.recordPosition(anyLong(), anyLong(), any(), anyDouble(), anyDouble(),
                anyDouble(), any(), any()))
                .thenReturn(new DeviceMotionStateService.MotionSnapshot(0, null, null, true, true, true));
    }

    private IngestPositionRequest request(double lat, double lng) {
        return request(lat, lng, Instant.now());
    }

    private IngestPositionRequest request(double lat, double lng, Instant recordedAt) {
        return new IngestPositionRequest(lat, lng, 40.0, 90.0, null, 5.0, true,
                null, null, null, null, null, recordedAt, null, null, null, null, null);
    }

    private Device activeDevice() {
        Device d = new Device();
        d.setId(10L);
        d.setTenantId(1L);
        d.setVehicleId(100L);
        d.setStatus(DeviceStatus.ACTIVE);
        d.setIngestToken("good-token");
        return d;
    }

    private void stubSaves() {
        when(positionRepository.save(any(Position.class))).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            p.setId(999L);
            return p;
        });
        when(currentPositionRepository.save(any(DeviceCurrentPosition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void rejectsUnknownToken() {
        when(deviceRepository.findByIngestToken("bad")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ingest("bad", request(12.97, 77.59)))
                .isInstanceOf(UnauthorizedException.class);
        verify(positionRepository, never()).save(any());
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> service.ingest("  ", request(12.97, 77.59)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsSuspendedDevice() {
        Device d = activeDevice();
        d.setStatus(DeviceStatus.SUSPENDED);
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> service.ingest("good-token", request(12.97, 77.59)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsInvalidCoordinates() {
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(activeDevice()));
        assertThatThrownBy(() -> service.ingest("good-token", request(0, 0)))
                .isInstanceOf(BadRequestException.class);
        verify(positionRepository, never()).save(any());
    }

    @Test
    void persistsValidPointWithTenantFromDeviceAndPublishesEvent() {
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(activeDevice()));
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.empty());
        stubSaves();

        IngestResult result = service.ingest("good-token", request(12.97, 77.59));

        assertThat(result.accepted()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.positionId()).isEqualTo(999L);

        ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(captor.capture());
        // Tenant/device/vehicle come from the authenticated device, never the payload.
        assertThat(captor.getValue().getTenantId()).isEqualTo(1L);
        assertThat(captor.getValue().getDeviceId()).isEqualTo(10L);
        assertThat(captor.getValue().getVehicleId()).isEqualTo(100L);

        verify(currentPositionRepository).save(any(DeviceCurrentPosition.class));
        verify(eventPublisher).publishEvent(any(PositionIngestedEvent.class));
    }

    @Test
    void queuesAiEvaluationThroughTheOutboxRatherThanCallingAiInline() {
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(activeDevice()));
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.empty());
        stubSaves();

        service.ingest("good-token", request(12.97, 77.59));

        ArgumentCaptor<AiEvaluationPayload> captor =
                ArgumentCaptor.forClass(AiEvaluationPayload.class);
        verify(outboxService).enqueue(captor.capture());
        AiEvaluationPayload payload = captor.getValue();
        assertThat(payload.tenantId()).isEqualTo(1L);
        assertThat(payload.deviceId()).isEqualTo(10L);
        assertThat(payload.vehicleId()).isEqualTo(100L);
        // A positive gap is guaranteed: a fabricated 0.5s substitution is gone.
        assertThat(payload.timeGapSeconds()).isGreaterThan(0.0);
    }

    @Test
    void outOfOrderPacketIsStoredButDoesNotAdvanceLiveStateOrTriggerAi() {
        Device device = activeDevice();
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(device));

        Instant now = Instant.now();
        DeviceCurrentPosition current = new DeviceCurrentPosition();
        current.setDeviceId(10L);
        current.setTenantId(1L);
        current.setLatitude(12.90);
        current.setLongitude(77.50);
        current.setSpeed(30);
        current.setCourse(90);
        current.setState(DeviceState.RUNNING);
        current.setDeviceTime(now);
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.of(current));
        stubSaves();

        // A packet recorded five minutes BEFORE the live snapshot.
        IngestResult result = service.ingest("good-token",
                request(12.97, 77.59, now.minus(5, ChronoUnit.MINUTES)));

        assertThat(result.accepted()).isTrue();
        // Stored for audit...
        verify(positionRepository).save(any(Position.class));
        // ...but the live position is untouched and no AI evaluation is queued.
        verify(currentPositionRepository, never()).save(any(DeviceCurrentPosition.class));
        verify(outboxService, never()).enqueue(any());
        verify(motionStateService, never()).recordPosition(anyLong(), anyLong(), any(), anyDouble(),
                anyDouble(), anyDouble(), any(), any());
        // Recorded as a telemetry-quality metric instead.
        verify(motionStateService).recordQualityIssue(1L, 10L,
                DeviceMotionStateService.QualityIssue.OUT_OF_ORDER);
    }

    @Test
    void duplicatePacketIsSuppressedEntirely() {
        Device device = activeDevice();
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(device));

        Instant now = Instant.now();
        DeviceCurrentPosition current = new DeviceCurrentPosition();
        current.setDeviceId(10L);
        current.setTenantId(1L);
        current.setLatitude(12.97);
        current.setLongitude(77.59);
        current.setSpeed(0);
        current.setCourse(90);
        current.setState(DeviceState.IDLE);
        current.setDeviceTime(now);
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.of(current));

        IngestResult result = service.ingest("good-token", request(12.97, 77.59, now));

        assertThat(result.duplicate()).isTrue();
        verify(positionRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any());
        verify(motionStateService).recordQualityIssue(1L, 10L,
                DeviceMotionStateService.QualityIssue.DUPLICATE);
    }

    @Test
    void continuousStationarySecondsFromMotionStateReachTheEvaluator() {
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(activeDevice()));
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.empty());
        stubSaves();
        when(motionStateService.recordPosition(eq(1L), eq(10L), any(), anyDouble(), anyDouble(),
                anyDouble(), any(), any()))
                .thenReturn(new DeviceMotionStateService.MotionSnapshot(
                        1800, Instant.now(), null, false, false, true));

        service.ingest("good-token", request(12.97, 77.59));

        ArgumentCaptor<AiEvaluationPayload> captor =
                ArgumentCaptor.forClass(AiEvaluationPayload.class);
        verify(outboxService).enqueue(captor.capture());
        // 30 minutes of accumulated idling - impossible to reach from a single
        // packet gap, which is exactly the bug this replaces.
        assertThat(captor.getValue().stationarySeconds()).isEqualTo(1800.0);
    }
}
