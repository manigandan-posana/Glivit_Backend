package com.glivt.device.dto;

public record PlaybackEventMarker(
        String t,
        double lat,
        double lng,
        String eventType
) {}
