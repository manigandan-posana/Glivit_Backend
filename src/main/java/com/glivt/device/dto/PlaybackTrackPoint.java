package com.glivt.device.dto;

public record PlaybackTrackPoint(
        String t,
        double lat,
        double lng,
        double speed,
        double course,
        Boolean ignition,
        boolean gpsValid
) {}
