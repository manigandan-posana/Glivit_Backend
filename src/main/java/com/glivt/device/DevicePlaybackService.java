package com.glivt.device;

import com.glivt.common.exception.BadRequestException;
import com.glivt.device.dto.PlaybackEventMarker;
import com.glivt.device.dto.PlaybackResponse;
import com.glivt.device.dto.PlaybackStopMarker;
import com.glivt.device.dto.PlaybackTrackPoint;
import com.glivt.event.Event;
import com.glivt.event.EventRepository;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevicePlaybackService {

    private final PositionRepository positionRepository;
    private final EventRepository eventRepository;

    public DevicePlaybackService(PositionRepository positionRepository, EventRepository eventRepository) {
        this.positionRepository = positionRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public PlaybackResponse getPlayback(Long tenantId, Long deviceId, Instant from, Instant to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to dates are required");
        }
        if (Duration.between(from, to).toDays() > 31) {
            throw new BadRequestException("Playback range cannot exceed 31 days");
        }

        List<Position> positions = positionRepository
                .findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(tenantId, deviceId, from, to);

        List<Event> events = eventRepository
                .findByTenantIdAndDeviceIdAndServerTimeBetweenOrderByServerTimeAsc(tenantId, deviceId, from, to);

        List<PlaybackTrackPoint> points = new ArrayList<>();
        double distanceKm = 0.0;
        Position lastPos = null;

        for (Position p : positions) {
            points.add(new PlaybackTrackPoint(
                    DateTimeFormatter.ISO_INSTANT.format(p.getDeviceTime()),
                    p.getLatitude(),
                    p.getLongitude(),
                    p.getSpeed(),
                    p.getCourse(),
                    p.getIgnition(),
                    p.isGpsValid()
            ));

            if (lastPos != null && p.isGpsValid() && lastPos.isGpsValid()) {
                distanceKm += calculateDistance(lastPos.getLatitude(), lastPos.getLongitude(),
                        p.getLatitude(), p.getLongitude());
            }
            lastPos = p;
        }

        List<PlaybackEventMarker> eventMarkers = events.stream().map(e -> new PlaybackEventMarker(
                DateTimeFormatter.ISO_INSTANT.format(e.getServerTime()),
                e.getLatitude() != null ? e.getLatitude() : (points.isEmpty() ? 0 : points.get(0).lat()),
                e.getLongitude() != null ? e.getLongitude() : (points.isEmpty() ? 0 : points.get(0).lng()),
                e.getEventType()
        )).toList();

        // Very basic stop extraction logic for the demo/fallback
        List<PlaybackStopMarker> stopMarkers = extractStops(positions);

        return new PlaybackResponse(
                deviceId,
                DateTimeFormatter.ISO_INSTANT.format(from),
                DateTimeFormatter.ISO_INSTANT.format(to),
                positions.size(),
                points.size(),
                distanceKm,
                points,
                eventMarkers,
                stopMarkers
        );
    }

    private List<PlaybackStopMarker> extractStops(List<Position> positions) {
        List<PlaybackStopMarker> stops = new ArrayList<>();
        Position stopStart = null;

        for (int i = 0; i < positions.size(); i++) {
            Position p = positions.get(i);
            if (p.getSpeed() < 2.0 && p.isGpsValid()) {
                if (stopStart == null) {
                    stopStart = p;
                }
            } else {
                if (stopStart != null) {
                    long minutes = Duration.between(stopStart.getDeviceTime(), p.getDeviceTime()).toMinutes();
                    if (minutes >= 5) {
                        stops.add(new PlaybackStopMarker(
                                DateTimeFormatter.ISO_INSTANT.format(stopStart.getDeviceTime()),
                                DateTimeFormatter.ISO_INSTANT.format(p.getDeviceTime()),
                                stopStart.getLatitude(),
                                stopStart.getLongitude(),
                                minutes
                        ));
                    }
                    stopStart = null;
                }
            }
        }
        
        if (stopStart != null) {
            Position last = positions.get(positions.size() - 1);
            long minutes = Duration.between(stopStart.getDeviceTime(), last.getDeviceTime()).toMinutes();
            if (minutes >= 5) {
                stops.add(new PlaybackStopMarker(
                        DateTimeFormatter.ISO_INSTANT.format(stopStart.getDeviceTime()),
                        DateTimeFormatter.ISO_INSTANT.format(last.getDeviceTime()),
                        stopStart.getLatitude(),
                        stopStart.getLongitude(),
                        minutes
                ));
            }
        }
        
        return stops;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the earth
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
