package com.glivt.device.dto;

import java.util.List;

public record PlaybackResponse(
        Long deviceId,
        String from,
        String to,
        int totalPoints,
        int returnedPoints,
        double distanceKm,
        List<PlaybackTrackPoint> points,
        List<PlaybackEventMarker> events,
        List<PlaybackStopMarker> stops
) {}
