package com.glivt.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glivt.ai.service.GpsFeatureService;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PositionIngestServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private PositionRepository positionRepository;
    @Mock private DeviceCurrentPositionRepository currentPositionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PositionIngestService service;

    @BeforeEach
    void setUp() {
        service = new PositionIngestService(deviceRepository, positionRepository,
                currentPositionRepository, new GpsFeatureService(), eventPublisher);
    }

    private IngestPositionRequest request(double lat, double lng) {
        return new IngestPositionRequest(lat, lng, 40.0, 90.0, null, 5.0, true,
                null, null, null, null, null, Instant.now(), null, null, null, null, null);
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
        Device device = activeDevice();
        when(deviceRepository.findByIngestToken("good-token")).thenReturn(Optional.of(device));
        when(currentPositionRepository.findById(10L)).thenReturn(Optional.empty());
        when(positionRepository.save(any(Position.class))).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            p.setId(999L);
            return p;
        });
        when(currentPositionRepository.save(any(DeviceCurrentPosition.class)))
                .thenAnswer(inv -> inv.getArgument(0));

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
}
