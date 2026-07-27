package com.glivt.device.dto;

public record PlaybackStopMarker(
        String from,
        String to,
        double lat,
        double lng,
        long minutes
) {}
