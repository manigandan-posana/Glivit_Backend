package com.glivt.position.dto;

import java.time.Instant;
import java.util.List;

/**
 * Route playback for a device over a time window. Long routes are simplified
 * for the map (returnedPoints &le; totalPoints) while reports still read the
 * full history. Each point carries speed / ignition / gps validity so the
 * client can render speed, ignition and signal timelines without extra calls.
 */
public record PlaybackResponse(
        long deviceId,
        Instant from,
        Instant to,
        int totalPoints,
        int returnedPoints,
        double distanceKm,
        List<TrackPoint> points,
        List<EventMarker> events,
        List<StopMarker> stops) {

    public record TrackPoint(
            Instant t,
            double lat,
            double lng,
            double speed,
            double course,
            Boolean ignition,
            boolean gpsValid) {
    }

    public record EventMarker(Instant t, double lat, double lng, String eventType) {
    }

    public record StopMarker(Instant from, Instant to, double lat, double lng, long minutes) {
    }
}
