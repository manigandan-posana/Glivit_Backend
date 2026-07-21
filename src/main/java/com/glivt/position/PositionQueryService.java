package com.glivt.position;

import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.position.dto.PlaybackResponse;
import com.glivt.position.dto.PositionDto;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantRepository;
import com.glivt.telemetry.TelemetrySettings;
import com.glivt.telemetry.TelemetrySettingsService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped read side for position history and route playback. Enforces
 * device ownership (IDOR), bounded date ranges and map-geometry simplification
 * so a single request never returns unbounded telemetry.
 */
@Service
public class PositionQueryService {

    /** Hard cap on simplified playback points regardless of range. */
    private static final int MAX_PLAYBACK_POINTS = 1500;
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int FALLBACK_MAX_DAYS = 90;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;
    private final TenantRepository tenantRepository;
    private final TelemetrySettingsService settingsService;

    public PositionQueryService(DeviceRepository deviceRepository,
                                PositionRepository positionRepository,
                                TenantRepository tenantRepository,
                                TelemetrySettingsService settingsService) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
        this.tenantRepository = tenantRepository;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public PageResponse<PositionDto> history(Long tenantId, Long deviceId, Instant from, Instant to,
                                             Pageable pageable) {
        requireDevice(tenantId, deviceId);
        Range range = resolveRange(tenantId, from, to);
        Page<Position> page = positionRepository.findByTenantIdAndDeviceIdAndDeviceTimeBetween(
                tenantId, deviceId, range.from(), range.to(), pageable);
        return PageResponse.from(page, PositionDto::from);
    }

    @Transactional(readOnly = true)
    public PlaybackResponse playback(Long tenantId, Long deviceId, Instant from, Instant to) {
        requireDevice(tenantId, deviceId);
        Range range = resolveRange(tenantId, from, to);
        List<Position> raw = positionRepository
                .findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(
                        tenantId, deviceId, range.from(), range.to());

        TelemetrySettings settings = settingsService.resolve(tenantId);
        double distanceKm = totalDistanceKm(raw);
        List<PlaybackResponse.EventMarker> events = eventMarkers(raw);
        List<PlaybackResponse.StopMarker> stops = stopMarkers(raw, settings);
        List<PlaybackResponse.TrackPoint> points = simplify(raw);

        return new PlaybackResponse(deviceId, range.from(), range.to(),
                raw.size(), points.size(), round2(distanceKm), points, events, stops);
    }

    private Device requireDevice(Long tenantId, Long deviceId) {
        return deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
    }

    private Range resolveRange(Long tenantId, Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW_HOURS, ChronoUnit.HOURS);
        if (!start.isBefore(end)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        int maxDays = tenantRepository.findById(tenantId)
                .map(Tenant::getMaxHistoryDays)
                .filter(d -> d > 0)
                .orElse(FALLBACK_MAX_DAYS);
        if (Duration.between(start, end).toDays() > maxDays) {
            throw new BadRequestException("Requested range exceeds the " + maxDays + "-day maximum");
        }
        return new Range(start, end);
    }

    /** Decimate to at most {@link #MAX_PLAYBACK_POINTS}, always keeping first and last. */
    private List<PlaybackResponse.TrackPoint> simplify(List<Position> raw) {
        List<PlaybackResponse.TrackPoint> out = new ArrayList<>();
        if (raw.isEmpty()) {
            return out;
        }
        int step = Math.max(1, (int) Math.ceil((double) raw.size() / MAX_PLAYBACK_POINTS));
        for (int i = 0; i < raw.size(); i += step) {
            out.add(toTrackPoint(raw.get(i)));
        }
        Position last = raw.get(raw.size() - 1);
        if (out.get(out.size() - 1).t() == null || !out.get(out.size() - 1).t().equals(last.getDeviceTime())) {
            out.add(toTrackPoint(last));
        }
        return out;
    }

    private PlaybackResponse.TrackPoint toTrackPoint(Position p) {
        return new PlaybackResponse.TrackPoint(
                p.getDeviceTime(), p.getLatitude(), p.getLongitude(),
                p.getSpeed(), p.getCourse(), p.getIgnition(), p.isGpsValid());
    }

    private List<PlaybackResponse.EventMarker> eventMarkers(List<Position> raw) {
        List<PlaybackResponse.EventMarker> markers = new ArrayList<>();
        for (Position p : raw) {
            if (p.getEventType() != null && !p.getEventType().isBlank()) {
                markers.add(new PlaybackResponse.EventMarker(
                        p.getDeviceTime(), p.getLatitude(), p.getLongitude(), p.getEventType()));
            }
        }
        return markers;
    }

    /**
     * Deterministic stop detection: consecutive readings below the idle-speed
     * threshold whose span meets the minimum-stop duration become one stop.
     */
    private List<PlaybackResponse.StopMarker> stopMarkers(List<Position> raw, TelemetrySettings settings) {
        List<PlaybackResponse.StopMarker> stops = new ArrayList<>();
        int i = 0;
        while (i < raw.size()) {
            if (raw.get(i).getSpeed() >= settings.idleSpeedKmh()) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < raw.size() && raw.get(j + 1).getSpeed() < settings.idleSpeedKmh()) {
                j++;
            }
            Position start = raw.get(i);
            Position end = raw.get(j);
            long minutes = Duration.between(start.getDeviceTime(), end.getDeviceTime()).toMinutes();
            if (minutes >= settings.minStopMinutes()) {
                stops.add(new PlaybackResponse.StopMarker(
                        start.getDeviceTime(), end.getDeviceTime(),
                        start.getLatitude(), start.getLongitude(), minutes));
            }
            i = j + 1;
        }
        return stops;
    }

    private double totalDistanceKm(List<Position> raw) {
        double km = 0;
        for (int i = 1; i < raw.size(); i++) {
            km += haversineKm(raw.get(i - 1), raw.get(i));
        }
        return km;
    }

    private double haversineKm(Position a, Position b) {
        double r = 6371.0088;
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Range(Instant from, Instant to) {
    }
}
