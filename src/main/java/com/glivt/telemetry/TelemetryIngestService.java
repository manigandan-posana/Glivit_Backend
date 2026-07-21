package com.glivt.telemetry;

import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ForbiddenException;
import com.glivt.common.exception.UnauthorizedException;
import com.glivt.device.Device;
import com.glivt.device.DeviceExpiry;
import com.glivt.device.DeviceRepository;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import com.glivt.telemetry.dto.IngestResult;
import com.glivt.telemetry.dto.PositionReport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production telemetry ingestion. Authenticates the device by IMEI + token,
 * validates and normalises each reading, persists append-only history with an
 * idempotency guard, atomically refreshes the current-position snapshot and
 * derives the device state on the backend.
 *
 * <p>Deliberately free of any AI / notification dependency so ingestion never
 * fails because a downstream (model, push) service is unavailable.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);
    private static final double MAX_PLAUSIBLE_SPEED_KMH = 400;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final TelemetrySettingsService settingsService;
    private final DeviceStateCalculator stateCalculator;

    public TelemetryIngestService(DeviceRepository deviceRepository,
                                  PositionRepository positionRepository,
                                  DeviceCurrentPositionRepository currentPositionRepository,
                                  TelemetrySettingsService settingsService,
                                  DeviceStateCalculator stateCalculator) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.settingsService = settingsService;
        this.stateCalculator = stateCalculator;
    }

    /**
     * @param strict when true (single-position endpoint) an invalid reading is
     *               reported as a 400; when false (batch) it is counted and skipped.
     */
    @Transactional
    public IngestResult ingest(String imei, String token, List<PositionReport> reports, boolean strict) {
        Device device = authenticate(imei, token);
        TelemetrySettings settings = settingsService.resolve(device.getTenantId());

        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        Position newest = null;

        for (PositionReport report : reports) {
            String reason = validate(report);
            if (reason != null) {
                if (strict) {
                    throw new BadRequestException(reason);
                }
                rejected++;
                log.debug("Rejected position for device {}: {}", device.getId(), reason);
                continue;
            }

            Instant serverTime = Instant.now();
            Instant deviceTime = report.deviceTime() != null ? report.deviceTime() : serverTime;
            String dedupKey = dedupKey(report, deviceTime);

            if (positionRepository.existsByDeviceIdAndDedupKey(device.getId(), dedupKey)) {
                duplicates++;
                continue;
            }

            Position position = toPosition(device, report, deviceTime, serverTime, dedupKey);
            try {
                positionRepository.saveAndFlush(position);
            } catch (DataIntegrityViolationException race) {
                // Concurrent retry inserted the same packet first - treat as duplicate.
                duplicates++;
                continue;
            }
            accepted++;
            if (newest == null || position.getDeviceTime().isAfter(newest.getDeviceTime())) {
                newest = position;
            }
        }

        DeviceState state = updateCurrent(device, newest, settings);
        return new IngestResult(device.getId(), accepted, duplicates, rejected, state);
    }

    private Device authenticate(String imei, String token) {
        if (imei == null || imei.isBlank() || token == null || token.isBlank()) {
            throw new UnauthorizedException("DEVICE_UNAUTHENTICATED",
                    "Device IMEI and token are required");
        }
        Device device = deviceRepository.findByImei(imei.trim())
                .orElseThrow(() -> new UnauthorizedException("DEVICE_UNKNOWN",
                        "Unknown or unauthenticated device"));
        // Constant-ish comparison; token is opaque so no user info is leaked.
        if (device.getDeviceToken() == null || !device.getDeviceToken().equals(token.trim())) {
            throw new UnauthorizedException("DEVICE_UNKNOWN", "Unknown or unauthenticated device");
        }
        if (device.getStatus() == DeviceStatus.SUSPENDED) {
            throw new ForbiddenException("Device is suspended");
        }
        if (DeviceExpiry.isExpired(device)) {
            throw new ForbiddenException("Device subscription has expired");
        }
        return device;
    }

    /** @return null when valid, otherwise a human-readable rejection reason. */
    private String validate(PositionReport r) {
        if (r.latitude() == null || r.longitude() == null) {
            return "latitude and longitude are required";
        }
        double lat = r.latitude();
        double lon = r.longitude();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return "coordinates out of range";
        }
        if (lat == 0 && lon == 0) {
            return "null-island coordinates rejected";
        }
        if (r.speed() != null && (r.speed() < 0 || r.speed() > MAX_PLAUSIBLE_SPEED_KMH)) {
            return "implausible speed";
        }
        if (r.deviceTime() != null && r.deviceTime().isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) {
            return "device time too far in the future";
        }
        return null;
    }

    private String dedupKey(PositionReport report, Instant deviceTime) {
        if (report.messageId() != null && !report.messageId().isBlank()) {
            return "msg:" + report.messageId().trim();
        }
        // Fallback: identical re-transmissions share the same device time.
        return "dt:" + deviceTime.toEpochMilli();
    }

    private Position toPosition(Device device, PositionReport r, Instant deviceTime,
                                Instant serverTime, String dedupKey) {
        Position p = new Position();
        p.setTenantId(device.getTenantId());
        p.setDeviceId(device.getId());
        p.setVehicleId(device.getVehicleId());
        p.setLatitude(r.latitude());
        p.setLongitude(r.longitude());
        p.setAltitude(r.altitude());
        p.setAccuracy(r.accuracy());
        p.setSpeed(r.speed() != null ? r.speed() : 0);
        p.setCourse(r.course() != null ? r.course() : 0);
        p.setDeviceTime(deviceTime);
        p.setServerTime(serverTime);
        p.setIgnition(r.ignition());
        p.setAc(r.ac());
        p.setDoor(r.door());
        p.setLockState(r.lockState());
        p.setBattery(r.battery());
        p.setExternalPower(r.externalPower());
        p.setGpsValid(r.gpsValid() == null || r.gpsValid());
        p.setSatellites(r.satellites());
        p.setNetworkSignal(r.networkSignal());
        p.setOdometer(r.odometer());
        p.setEngineHours(r.engineHours());
        p.setFuelLevel(r.fuelLevel());
        p.setEventType(r.eventType());
        p.setAddress(r.address());
        p.setDedupKey(dedupKey);
        return p;
    }

    /**
     * Atomically refresh the current-position snapshot with the newest accepted
     * reading and recompute the derived state. Out-of-order packets never move
     * the snapshot backwards.
     */
    private DeviceState updateCurrent(Device device, Position newest, TelemetrySettings settings) {
        DeviceCurrentPosition current = currentPositionRepository
                .findByDeviceIdAndTenantId(device.getId(), device.getTenantId())
                .orElse(null);

        if (newest != null && (current == null || current.getDeviceTime() == null
                || newest.getDeviceTime().isAfter(current.getDeviceTime()))) {
            if (current == null) {
                current = new DeviceCurrentPosition();
                current.setDeviceId(device.getId());
            }
            current.setTenantId(device.getTenantId());
            current.setVehicleId(device.getVehicleId());
            current.setPositionId(newest.getId());
            current.setLatitude(newest.getLatitude());
            current.setLongitude(newest.getLongitude());
            current.setSpeed(newest.getSpeed());
            current.setCourse(newest.getCourse());
            current.setIgnition(newest.getIgnition());
            current.setGpsValid(newest.isGpsValid());
            current.setExternalPower(newest.getExternalPower());
            current.setBattery(newest.getBattery());
            current.setFuelLevel(newest.getFuelLevel());
            current.setSatellites(newest.getSatellites());
            current.setNetworkSignal(newest.getNetworkSignal());
            current.setAddress(newest.getAddress());
            current.setDeviceTime(newest.getDeviceTime());
            current.setServerTime(newest.getServerTime());

            DeviceStateCalculator.Snapshot snapshot = new DeviceStateCalculator.Snapshot(
                    newest.getServerTime(), newest.getSpeed(), newest.getIgnition(),
                    newest.isGpsValid(), newest.getExternalPower());
            DeviceState state = stateCalculator.calculate(
                    device, snapshot, newest.getServerTime(), settings);
            current.setState(state);
            current.setUpdatedAt(Instant.now());
            currentPositionRepository.save(current);

            device.setLastPacketAt(newest.getServerTime());
            deviceRepository.save(device);
            return state;
        }

        return current != null ? current.getState() : DeviceState.NO_DATA;
    }
}
