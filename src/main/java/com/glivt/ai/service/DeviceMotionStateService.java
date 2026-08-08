package com.glivt.ai.service;

import com.glivt.ai.entity.DeviceMotionState;
import com.glivt.ai.repository.DeviceMotionStateRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains continuous stationary time per device.
 *
 * <p>The previous implementation derived "stationary duration" from the gap
 * between two consecutive packets, so it could never exceed the reporting
 * interval and the 15-minute idling rule never fired. Here the duration
 * accumulates across packets in a persistent row and is reset only when real
 * movement is observed.
 *
 * <p>Out-of-order and duplicate packets are recorded as telemetry-quality
 * counters and explicitly do not advance the motion state.
 */
@Service
public class DeviceMotionStateService {

    private static final Logger log = LoggerFactory.getLogger(DeviceMotionStateService.class);

    /** Below this speed the vehicle counts as stationary. */
    public static final double STATIONARY_SPEED_KPH = 3.0;
    /** Movement beyond this distance resets the stationary timer even at low speed. */
    public static final double MOVEMENT_RESET_METERS = 50.0;
    /** A gap longer than this means the device was offline; do not bridge it. */
    private static final long MAX_BRIDGEABLE_GAP_SECONDS = 3600;

    private final DeviceMotionStateRepository repository;

    public DeviceMotionStateService(DeviceMotionStateRepository repository) {
        this.repository = repository;
    }

    /** The motion facts the anomaly evaluator needs for one packet. */
    public record MotionSnapshot(
            double continuousStationarySeconds,
            Instant stationarySince,
            Instant lastMovementAt,
            boolean moving,
            boolean previouslyMoving,
            Boolean ignitionOn) {
    }

    /**
     * Advance the motion state with an in-order packet.
     *
     * <p>Joins the ingestion transaction: the motion row must commit atomically
     * with the position it was derived from, otherwise a rollback would leave
     * stationary time counted for a packet that was never stored.
     */
    @Transactional
    public MotionSnapshot recordPosition(Long tenantId, Long deviceId, Long vehicleId,
            double latitude, double longitude, double speedKph, Boolean ignitionOn,
            Instant deviceTime) {

        DeviceMotionState state = repository.findByDeviceIdAndTenantId(deviceId, tenantId)
                .orElseGet(() -> {
                    DeviceMotionState fresh = new DeviceMotionState();
                    fresh.setDeviceId(deviceId);
                    fresh.setTenantId(tenantId);
                    return fresh;
                });
        state.setVehicleId(vehicleId);
        state.setIgnitionOn(ignitionOn);

        Instant now = deviceTime != null ? deviceTime : Instant.now();
        Instant previousTime = state.getLastDeviceTime();

        double movedMeters = 0.0;
        if (state.getLastLatitude() != null && state.getLastLongitude() != null) {
            movedMeters = GeoMath.haversineMeters(state.getLastLatitude(), state.getLastLongitude(),
                    latitude, longitude);
        }

        boolean moving = speedKph >= STATIONARY_SPEED_KPH || movedMeters > MOVEMENT_RESET_METERS;

        if (moving) {
            // Real movement resets the continuous stationary timer.
            state.setContinuousStationarySeconds(0.0);
            state.setStationarySince(null);
            state.setLastMovementAt(now);
            state.setPreviouslyMoving(true);
        } else {
            if (state.getStationarySince() == null) {
                state.setStationarySince(now);
                state.setContinuousStationarySeconds(0.0);
            } else if (previousTime != null) {
                long gapSeconds = Duration.between(previousTime, now).getSeconds();
                if (gapSeconds > 0 && gapSeconds <= MAX_BRIDGEABLE_GAP_SECONDS) {
                    state.setContinuousStationarySeconds(
                            state.getContinuousStationarySeconds() + gapSeconds);
                } else if (gapSeconds > MAX_BRIDGEABLE_GAP_SECONDS) {
                    // The device was offline; do not claim it idled the whole time.
                    state.setStationarySince(now);
                    state.setContinuousStationarySeconds(0.0);
                }
            }
            state.setPreviouslyMoving(false);
        }

        state.setLastDeviceTime(now);
        state.setLastLatitude(latitude);
        state.setLastLongitude(longitude);
        DeviceMotionState saved = repository.save(state);

        return new MotionSnapshot(
                saved.getContinuousStationarySeconds(),
                saved.getStationarySince(),
                saved.getLastMovementAt(),
                moving,
                saved.isPreviouslyMoving(),
                saved.getIgnitionOn());
    }

    /**
     * Record a telemetry-quality event without advancing motion state. Used for
     * out-of-order, duplicate and low-confidence packets, which must never reach
     * real-time anomaly scoring.
     */
    @Transactional
    public void recordQualityIssue(Long tenantId, Long deviceId, QualityIssue issue) {
        DeviceMotionState state = repository.findByDeviceIdAndTenantId(deviceId, tenantId)
                .orElseGet(() -> {
                    DeviceMotionState fresh = new DeviceMotionState();
                    fresh.setDeviceId(deviceId);
                    fresh.setTenantId(tenantId);
                    return fresh;
                });
        switch (issue) {
            case OUT_OF_ORDER -> state.setOutOfOrderPackets(state.getOutOfOrderPackets() + 1);
            case DUPLICATE -> state.setDuplicatePackets(state.getDuplicatePackets() + 1);
            case LOW_CONFIDENCE -> state.setLowConfidencePackets(state.getLowConfidencePackets() + 1);
        }
        repository.save(state);
        log.debug("telemetry.quality tenantId={} deviceId={} issue={}", tenantId, deviceId, issue);
    }

    public enum QualityIssue {
        OUT_OF_ORDER,
        DUPLICATE,
        LOW_CONFIDENCE
    }
}
