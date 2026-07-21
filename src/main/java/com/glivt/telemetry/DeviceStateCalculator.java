package com.glivt.telemetry;

import com.glivt.device.Device;
import com.glivt.device.DeviceExpiry;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceState;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deterministic, backend-owned derivation of {@link DeviceState}. The mobile
 * client never decides state - it only renders what the server computed.
 *
 * <p>The same function is used both when a fresh packet is ingested (passing the
 * packet's server time as {@code now}) and when a background job re-evaluates
 * staleness (passing the real wall clock), so "moving" and "offline" stay
 * consistent.
 */
@Component
public class DeviceStateCalculator {

    /**
     * @param device      owning device (status / expiry / timezone)
     * @param snapshot    latest known telemetry, or {@code null} if none reported
     * @param now         reference time (packet server time on ingest, wall clock on recalc)
     * @param settings    resolved tenant thresholds
     */
    public DeviceState calculate(Device device, Snapshot snapshot, Instant now, TelemetrySettings settings) {
        if (device.getStatus() == DeviceStatus.SUSPENDED) {
            return DeviceState.SUSPENDED;
        }
        if (DeviceExpiry.isExpired(device)) {
            return DeviceState.EXPIRED;
        }
        if (snapshot == null || snapshot.serverTime() == null) {
            return DeviceState.NO_DATA;
        }
        // Stale beyond the offline window => OFFLINE regardless of the last reading.
        long ageSeconds = Math.max(0, now.getEpochSecond() - snapshot.serverTime().getEpochSecond());
        if (ageSeconds > settings.offlineTimeoutSeconds()) {
            return DeviceState.OFFLINE;
        }
        // Fresh data: evaluate the reading itself.
        if (snapshot.externalPower() != null
                && snapshot.externalPower() <= settings.powerMinVoltage()) {
            return DeviceState.POWER_DISCONNECTED;
        }
        if (settings.requireGpsValid() && !snapshot.gpsValid()) {
            return DeviceState.GPS_INVALID;
        }
        if (snapshot.speed() >= settings.idleSpeedKmh()) {
            return DeviceState.RUNNING;
        }
        if (Boolean.TRUE.equals(snapshot.ignition())) {
            return DeviceState.IDLE;
        }
        return DeviceState.STOPPED;
    }

    /** Minimal telemetry inputs needed for state derivation. */
    public record Snapshot(
            Instant serverTime,
            double speed,
            Boolean ignition,
            boolean gpsValid,
            Double externalPower) {
    }
}
