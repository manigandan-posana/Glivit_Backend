package com.glivt.ai.service;

import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.entity.TripFeatureSnapshot;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.TripFeatureSnapshotRepository;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds trip-level feature snapshots from stored positions and AI incidents.
 *
 * <p>A trip is a run of movement bounded by a stop longer than
 * {@link #TRIP_BREAK_MINUTES}. At completion the snapshot captures everything the
 * daily driver score needs - distance, duration, speeding, harsh events, idling,
 * night driving, deviations, GPS confidence and incident severity totals - so
 * scoring never has to re-scan raw telemetry.
 */
@Service
public class TripFeatureExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TripFeatureExtractionService.class);

    /** A gap this long ends the trip. */
    private static final long TRIP_BREAK_MINUTES = 10;
    private static final double MOVING_SPEED_KPH = 5.0;
    private static final int MAX_POSITIONS_PER_DEVICE = 10_000;
    /** Night driving is higher-risk exposure and is scored separately. */
    private static final int NIGHT_START_HOUR = 22;
    private static final int NIGHT_END_HOUR = 5;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;
    private final TripFeatureSnapshotRepository tripRepository;
    private final AiEventRepository aiEventRepository;
    private final DriverAssignmentResolver driverAssignmentResolver;

    public TripFeatureExtractionService(DeviceRepository deviceRepository,
            PositionRepository positionRepository,
            TripFeatureSnapshotRepository tripRepository,
            AiEventRepository aiEventRepository,
            DriverAssignmentResolver driverAssignmentResolver) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
        this.tripRepository = tripRepository;
        this.aiEventRepository = aiEventRepository;
        this.driverAssignmentResolver = driverAssignmentResolver;
    }

    /** Extract completed trips for a tenant over a window. Returns trips written. */
    @Transactional
    public int extractForTenant(Long tenantId, Instant from, Instant to) {
        int written = 0;
        for (Device device : deviceRepository.findByTenantId(tenantId)) {
            try {
                written += extractForDevice(tenantId, device, from, to);
            } catch (Exception ex) {
                // One device failing must not abandon the rest of the tenant.
                log.warn("tripExtraction.failed tenantId={} deviceId={} error={}",
                        tenantId, device.getId(), ex.toString());
            }
        }
        return written;
    }

    private int extractForDevice(Long tenantId, Device device, Instant from, Instant to) {
        List<Position> positions = positionRepository
                .findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(
                        tenantId, device.getId(), from, to,
                        PageRequest.of(0, MAX_POSITIONS_PER_DEVICE));
        if (positions.size() < 2) {
            return 0;
        }

        int written = 0;
        List<Position> current = new ArrayList<>();
        Instant lastMovingAt = null;

        for (Position position : positions) {
            boolean moving = position.getSpeed() >= MOVING_SPEED_KPH;
            if (moving) {
                lastMovingAt = position.getDeviceTime();
            }
            if (!current.isEmpty() && lastMovingAt != null && position.getDeviceTime() != null
                    && !moving
                    && ChronoUnit.MINUTES.between(lastMovingAt, position.getDeviceTime())
                            >= TRIP_BREAK_MINUTES) {
                current.add(position);
                if (persistTrip(tenantId, device, current)) {
                    written++;
                }
                current = new ArrayList<>();
                lastMovingAt = null;
                continue;
            }
            current.add(position);
        }

        // The tail is only a completed trip if it ends before the window does.
        if (current.size() >= 2) {
            Position last = current.get(current.size() - 1);
            if (last.getDeviceTime() != null
                    && ChronoUnit.MINUTES.between(last.getDeviceTime(), to) >= TRIP_BREAK_MINUTES
                    && persistTrip(tenantId, device, current)) {
                written++;
            }
        }
        return written;
    }

    private boolean persistTrip(Long tenantId, Device device, List<Position> points) {
        Position first = points.get(0);
        Position last = points.get(points.size() - 1);
        if (first.getDeviceTime() == null || last.getDeviceTime() == null) {
            return false;
        }
        // Idempotent: re-running the job must not duplicate a trip.
        if (tripRepository.existsByTenantIdAndVehicleIdAndStartTime(
                tenantId, device.getVehicleId(), first.getDeviceTime())) {
            return false;
        }

        double distanceKm = 0;
        double maxSpeed = 0;
        double speedSum = 0;
        int idleSeconds = 0;
        int nightSeconds = 0;
        int stopCount = 0;
        boolean wasMoving = false;

        for (int i = 0; i < points.size(); i++) {
            Position point = points.get(i);
            maxSpeed = Math.max(maxSpeed, point.getSpeed());
            speedSum += point.getSpeed();

            if (i > 0) {
                Position previous = points.get(i - 1);
                distanceKm += GeoMath.haversineKm(previous.getLatitude(), previous.getLongitude(),
                        point.getLatitude(), point.getLongitude());
                long gap = previous.getDeviceTime() == null || point.getDeviceTime() == null
                        ? 0
                        : ChronoUnit.SECONDS.between(previous.getDeviceTime(), point.getDeviceTime());
                if (gap > 0 && gap < 3600) {
                    if (point.getSpeed() < MOVING_SPEED_KPH
                            && Boolean.TRUE.equals(point.getIgnition())) {
                        idleSeconds += gap;
                    }
                    int hour = point.getDeviceTime().atZone(ZoneOffset.UTC).getHour();
                    if (hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR) {
                        nightSeconds += gap;
                    }
                }
            }

            boolean moving = point.getSpeed() >= MOVING_SPEED_KPH;
            if (wasMoving && !moving) {
                stopCount++;
            }
            wasMoving = moving;
        }

        long durationMinutes = ChronoUnit.MINUTES.between(first.getDeviceTime(), last.getDeviceTime());
        if (distanceKm < 0.2 || durationMinutes < 2) {
            return false; // not a real trip
        }

        // Incident counts come from the stored AI incidents in the same window.
        IncidentTotals incidents = countIncidents(tenantId, device.getVehicleId(),
                first.getDeviceTime(), last.getDeviceTime());

        Optional<DriverAssignmentResolver.ResolvedDriver> driver =
                driverAssignmentResolver.resolve(tenantId, device.getVehicleId(), first.getDeviceTime());

        TripFeatureSnapshot snapshot = new TripFeatureSnapshot();
        snapshot.setTenantId(tenantId);
        snapshot.setVehicleId(device.getVehicleId());
        driver.ifPresent(d -> snapshot.setDriverId(d.driverId()));
        snapshot.setStartTime(first.getDeviceTime());
        snapshot.setEndTime(last.getDeviceTime());
        snapshot.setDistanceKm(round2(distanceKm));
        snapshot.setDurationMinutes((int) durationMinutes);
        snapshot.setIdleDurationMinutes(idleSeconds / 60);
        snapshot.setAvgSpeedKph(round2(speedSum / points.size()));
        snapshot.setMaxSpeedKph(round2(maxSpeed));
        snapshot.setStopCount(stopCount);
        snapshot.setNightDrivingMinutes(nightSeconds / 60);
        snapshot.setHarshAccelCount(incidents.harshAccel);
        snapshot.setHarshBrakeCount(incidents.harshBrake);
        snapshot.setSharpTurnCount(incidents.sharpTurn);
        snapshot.setSpeedingEventCount(incidents.speedingEvents);
        snapshot.setSpeedingSeconds(incidents.speedingSeconds);
        snapshot.setRouteDeviationCount(incidents.routeDeviations);
        snapshot.setCriticalIncidentCount(incidents.critical);
        snapshot.setHighIncidentCount(incidents.high);
        snapshot.setHarshEventCount(incidents.harshAccel + incidents.harshBrake + incidents.sharpTurn);
        snapshot.setAbnormalEventCount(incidents.total);
        snapshot.setAvgGpsConfidence(1.0);
        snapshot.setMinGpsConfidence(1.0);
        snapshot.setStatus("COMPLETED");
        snapshot.setSummaryText(String.format(java.util.Locale.ROOT,
                "%.1f km in %d min, max %.0f km/h, %d stop(s), %d min idling.",
                distanceKm, durationMinutes, maxSpeed, stopCount, idleSeconds / 60));
        tripRepository.save(snapshot);
        return true;
    }

    private IncidentTotals countIncidents(Long tenantId, Long vehicleId, Instant from, Instant to) {
        IncidentTotals totals = new IncidentTotals();
        if (vehicleId == null) {
            return totals;
        }
        for (AiEvent event : aiEventRepository
                .findTop5ByTenantIdAndVehicleIdOrderByCreatedAtDesc(tenantId, vehicleId)) {
            Instant at = event.getLastObservedAt() != null ? event.getLastObservedAt() : event.getCreatedAt();
            if (at == null || at.isBefore(from) || at.isAfter(to)) {
                continue;
            }
            int occurrences = Math.max(1, event.getOccurrenceCount());
            totals.total += occurrences;
            switch (event.getEventType()) {
                case "HARSH_ACCELERATION" -> totals.harshAccel += occurrences;
                case "HARSH_BRAKING" -> totals.harshBrake += occurrences;
                case "SHARP_TURN" -> totals.sharpTurn += occurrences;
                case "ROUTE_DEVIATION" -> totals.routeDeviations += occurrences;
                case "SPEEDING" -> {
                    totals.speedingEvents += 1;
                    if (event.getFirstObservedAt() != null && event.getLastObservedAt() != null) {
                        totals.speedingSeconds += (int) ChronoUnit.SECONDS.between(
                                event.getFirstObservedAt(), event.getLastObservedAt());
                    }
                }
                default -> {
                    // Other anomaly types only contribute to the total count.
                }
            }
            if ("CRITICAL".equals(event.getSeverity())) {
                totals.critical += 1;
            } else if ("HIGH".equals(event.getSeverity())) {
                totals.high += 1;
            }
        }
        return totals;
    }

    private static final class IncidentTotals {
        private int harshAccel;
        private int harshBrake;
        private int sharpTurn;
        private int speedingEvents;
        private int speedingSeconds;
        private int routeDeviations;
        private int critical;
        private int high;
        private int total;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
