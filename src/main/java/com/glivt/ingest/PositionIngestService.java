package com.glivt.ingest;

import com.glivt.ai.dto.GpsFeatures;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GPS ingestion pipeline. Authenticates the device by token, resolves the tenant
 * server-side, validates the packet, persists the raw point, updates the current
 * snapshot with a deterministically-derived state, and enqueues an outbox entry
 * for asynchronous AI evaluation.
 *
 * <p>Ingestion never depends on AI. The outbox row commits with the position and
 * a separate worker performs the scoring, so if Python or Ollama are down the
 * position is still stored, the live map still updates and the device still gets
 * a fast response.
 *
 * <p>Out-of-order packets are handled explicitly: they are stored for audit but
 * do not move the live position, do not advance motion state and are never sent
 * to real-time anomaly scoring - where a negative time gap previously became a
 * fabricated 0.5-second gap and produced impossible-speed false positives.
 */
@Service
public class PositionIngestService {

    private static final Logger log = LoggerFactory.getLogger(PositionIngestService.class);

    /** Below this GPS confidence a packet is too unreliable to score. */
    private static final double MIN_AI_CONFIDENCE = 0.2;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final GpsFeatureService gpsFeatureService;
    private final DeviceMotionStateService motionStateService;
    private final AiPositionOutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public PositionIngestService(DeviceRepository deviceRepository,
                                 PositionRepository positionRepository,
                                 DeviceCurrentPositionRepository currentPositionRepository,
                                 GpsFeatureService gpsFeatureService,
                                 DeviceMotionStateService motionStateService,
                                 AiPositionOutboxService outboxService,
                                 ApplicationEventPublisher eventPublisher) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.gpsFeatureService = gpsFeatureService;
        this.motionStateService = motionStateService;
        this.outboxService = outboxService;
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

        // Ignore exact duplicate packets (same place & time) - no persistence, no AI.
        if (features.duplicate()) {
            motionStateService.recordQualityIssue(device.getTenantId(), device.getId(),
                    DeviceMotionStateService.QualityIssue.DUPLICATE);
            return new IngestResult(true, true,
                    previous != null ? previous.getState().name() : "NO_DATA",
                    features.gpsConfidence(), null);
        }

        Position position = positionRepository.save(toPosition(device, req, lat, lng, deviceSpeed,
                heading, recordedAt, receivedAt, features));

        DeviceState state = deriveState(req.ignitionOn(), features.calculatedSpeedKph(),
                features.coordinateValid());

        if (features.outOfOrder()) {
            // Stored above for audit, but it must not rewrite the present: the
            // live location stays on the newest packet, motion state is not
            // advanced, and no anomaly scoring is performed. The packet is
            // recorded as a telemetry-quality metric instead.
            motionStateService.recordQualityIssue(device.getTenantId(), device.getId(),
                    DeviceMotionStateService.QualityIssue.OUT_OF_ORDER);
            log.debug("gps.outOfOrder tenantId={} deviceId={} recordedAt={} gapSeconds={}",
                    device.getTenantId(), device.getId(), recordedAt,
                    features.timeFromPreviousSeconds());
            eventPublisher.publishEvent(new PositionIngestedEvent(device.getTenantId(),
                    device.getId(), position.getId(), true));
            return new IngestResult(true, false,
                    previous != null ? previous.getState().name() : state.name(),
                    features.gpsConfidence(), position.getId());
        }

        upsertCurrent(device, position, state, features);

        // Continuous stationary time is tracked persistently per device, so the
        // idling rule can actually be reached.
        DeviceMotionStateService.MotionSnapshot motion = motionStateService.recordPosition(
                device.getTenantId(), device.getId(), device.getVehicleId(), lat, lng,
                deviceSpeed, req.ignitionOn(), recordedAt);

        if (features.gpsConfidence() < MIN_AI_CONFIDENCE) {
            motionStateService.recordQualityIssue(device.getTenantId(), device.getId(),
                    DeviceMotionStateService.QualityIssue.LOW_CONFIDENCE);
            eventPublisher.publishEvent(new PositionIngestedEvent(device.getTenantId(),
                    device.getId(), position.getId(), false));
            return new IngestResult(true, false, state.name(), features.gpsConfidence(),
                    position.getId());
        }

        // A device-reported speed limit is deliberately NOT forwarded; the limit
        // is resolved server-side during evaluation.
        outboxService.enqueue(new AiEvaluationPayload(
                device.getTenantId(),
                device.getId(),
                device.getVehicleId(),
                position.getId(),
                lat,
                lng,
                deviceSpeed,
                features.calculatedSpeedKph(),
                features.accelerationMps2(),
                features.headingChangeDegrees(),
                features.distanceFromPreviousMeters(),
                features.timeFromPreviousSeconds(),
                req.accuracyMeters(),
                features.gpsConfidence(),
                motion.continuousStationarySeconds(),
                req.ignitionOn(),
                recordedAt));

        // Live-map streaming only; AI evaluation is driven by the outbox.
        eventPublisher.publishEvent(new PositionIngestedEvent(device.getTenantId(),
                device.getId(), position.getId(), false));

        return new IngestResult(true, false, state.name(), features.gpsConfidence(), position.getId());
    }

    private Position toPosition(Device device, IngestPositionRequest req, double lat, double lng,
            double deviceSpeed, double heading, Instant recordedAt, Instant receivedAt,
            GpsFeatures features) {
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
        return position;
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
