package com.glivt.ingest;

import com.glivt.ai.dto.GpsFeatures;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GPS ingestion pipeline. Authenticates the device by token, resolves the tenant
 * server-side, validates the packet, persists the raw point, updates the current
 * snapshot with a deterministically-derived state, and publishes an after-commit
 * event for asynchronous AI evaluation. Core ingestion never depends on AI: if
 * Python/Ollama are down the position is still stored and the location updated.
 */
@Service
public class PositionIngestService {

    private static final double DEFAULT_SPEED_LIMIT_KPH = 80.0;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final GpsFeatureService gpsFeatureService;
    private final ApplicationEventPublisher eventPublisher;

    public PositionIngestService(DeviceRepository deviceRepository,
                                 PositionRepository positionRepository,
                                 DeviceCurrentPositionRepository currentPositionRepository,
                                 GpsFeatureService gpsFeatureService,
                                 ApplicationEventPublisher eventPublisher) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.gpsFeatureService = gpsFeatureService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public IngestResult ingest(String deviceToken, IngestPositionRequest req) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new UnauthorizedException("DEVICE_UNAUTHORIZED", "Missing device token");
        }
        Device device = deviceRepository.findByIngestToken(deviceToken)
                .orElseThrow(() -> new UnauthorizedException("DEVICE_UNAUTHORIZED", "Invalid device token"));
        if (device.getStatus() == DeviceStatus.SUSPENDED || device.getStatus() == DeviceStatus.EXPIRED) {
            throw new UnauthorizedException("DEVICE_DISABLED", "Device is not active");
        }

        double lat = req.latitude();
        double lng = req.longitude();
        if (!gpsFeatureService.coordinateValid(lat, lng)) {
            // Reject invalid coordinates so they can never corrupt the route.
            throw new BadRequestException("Invalid GPS coordinates");
        }

        Instant receivedAt = Instant.now();
        Instant recordedAt = req.recordedAt() != null ? req.recordedAt() : receivedAt;
        double deviceSpeed = req.speedKph() != null ? req.speedKph() : 0.0;
        double heading = req.heading() != null ? req.heading() : 0.0;

        DeviceCurrentPosition previous = currentPositionRepository.findById(device.getId()).orElse(null);
        GpsFeatures features = gpsFeatureService.compute(previous, lat, lng, deviceSpeed, heading,
                req.accuracyMeters(), recordedAt, receivedAt);

        // Ignore exact duplicate packets (same place & time) — no persistence, no AI.
        if (features.duplicate()) {
            return new IngestResult(true, true, previous != null ? previous.getState().name() : "NO_DATA",
                    features.gpsConfidence(), null);
        }

        Position position = new Position();
        position.setTenantId(device.getTenantId());
        position.setDeviceId(device.getId());
        position.setVehicleId(device.getVehicleId());
        position.setLatitude(lat);
        position.setLongitude(lng);
        position.setSpeed(deviceSpeed);
        position.setCourse(heading);
        position.setAltitude(req.altitude());
        position.setAccuracy(req.accuracyMeters());
        position.setIgnition(req.ignitionOn());
        position.setBattery(req.batteryLevel());
        position.setExternalPower(req.externalPower());
        position.setOdometer(req.odometerKm());
        position.setEngineHours(req.engineHours());
        position.setFuelLevel(req.fuelLevel());
        position.setSatellites(req.satelliteCount());
        position.setNetworkSignal(req.networkSignal());
        position.setEventType(req.eventType());
        position.setGpsValid(features.coordinateValid());
        position.setDeviceTime(recordedAt);
        position.setServerTime(receivedAt);
        position = positionRepository.save(position);

        DeviceState state = deriveState(req.ignitionOn(), features.calculatedSpeedKph(),
                features.coordinateValid());

        // Only advance the live location for in-order, valid points.
        if (!features.outOfOrder()) {
            upsertCurrent(device, position, state, features);
        }

        double speedLimit = req.speedLimitKph() != null ? req.speedLimitKph() : DEFAULT_SPEED_LIMIT_KPH;
        // AFTER_COMMIT listener will kick off asynchronous AI evaluation.
        eventPublisher.publishEvent(new PositionIngestedEvent(position, features, speedLimit));

        return new IngestResult(true, false, state.name(), features.gpsConfidence(), position.getId());
    }

    private void upsertCurrent(Device device, Position position, DeviceState state, GpsFeatures features) {
        DeviceCurrentPosition current = currentPositionRepository.findById(device.getId())
                .orElseGet(() -> {
                    DeviceCurrentPosition c = new DeviceCurrentPosition();
                    c.setDeviceId(device.getId());
                    return c;
                });
        current.setTenantId(device.getTenantId());
        current.setVehicleId(device.getVehicleId());
        current.setPositionId(position.getId());
        current.setLatitude(position.getLatitude());
        current.setLongitude(position.getLongitude());
        current.setSpeed(position.getSpeed());
        current.setCourse(position.getCourse());
        current.setIgnition(position.getIgnition());
        current.setGpsValid(features.coordinateValid());
        current.setState(state);
        current.setDeviceTime(position.getDeviceTime());
        current.setServerTime(position.getServerTime());
        current.setUpdatedAt(Instant.now());
        currentPositionRepository.save(current);
    }

    private static DeviceState deriveState(Boolean ignitionOn, double calcSpeedKph, boolean coordinateValid) {
        if (!coordinateValid) {
            return DeviceState.NO_DATA;
        }
        if (!Boolean.TRUE.equals(ignitionOn)) {
            return DeviceState.STOPPED;
        }
        return calcSpeedKph < 3.0 ? DeviceState.IDLE : DeviceState.RUNNING;
    }
}
