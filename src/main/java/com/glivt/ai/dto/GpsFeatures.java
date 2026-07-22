package com.glivt.ai.dto;

/**
 * Deterministic, evidence-based features derived in Java from the current and
 * previous GPS point. These are the safety-critical numbers — Qwen never
 * computes them. They feed both the anomaly detector and the stored evidence.
 */
public record GpsFeatures(
        double distanceFromPreviousMeters,
        double timeFromPreviousSeconds,
        double calculatedSpeedKph,
        double headingChangeDegrees,
        double accelerationMps2,
        double stationaryDurationSeconds,
        double dataDelaySeconds,
        double gpsConfidence,
        boolean coordinateValid,
        boolean timestampValid,
        boolean duplicate,
        boolean outOfOrder) {

    public static GpsFeatures firstPoint(double dataDelaySeconds, boolean coordinateValid,
                                         boolean timestampValid) {
        return new GpsFeatures(0, 0, 0, 0, 0, 0, dataDelaySeconds,
                coordinateValid ? 1.0 : 0.0, coordinateValid, timestampValid, false, false);
    }
}
