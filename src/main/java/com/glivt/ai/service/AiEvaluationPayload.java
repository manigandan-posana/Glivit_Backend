package com.glivt.ai.service;

import java.time.Instant;

/**
 * Everything the asynchronous AI evaluator needs about one GPS position,
 * captured at ingestion time and serialised into the outbox.
 *
 * <p>Carrying a self-contained snapshot (rather than re-reading the position
 * later) keeps the worker cheap and means an evaluation still reflects the
 * packet as received even if the live snapshot has since moved on.
 *
 * <p>Note what is absent: any speed limit reported by the device. Limits are
 * resolved server-side by {@link SpeedLimitResolver}.
 */
public record AiEvaluationPayload(
        Long tenantId,
        Long deviceId,
        Long vehicleId,
        Long positionId,
        double latitude,
        double longitude,
        double speedKph,
        double calculatedSpeedKph,
        double accelerationMps2,
        double headingChangeDegrees,
        double locationJumpMeters,
        double timeGapSeconds,
        Double gpsAccuracyMeters,
        double gpsConfidence,
        /**
         * Continuous stationary time from persistent per-device motion state -
         * not the gap between two packets, which could never reach the idling
         * threshold.
         */
        double stationarySeconds,
        Boolean ignitionOn,
        Instant recordedAt) {

    /** Compact constructor guarding the invariants the AI service relies on. */
    public AiEvaluationPayload {
        // Out-of-order packets are filtered before enqueueing, so a non-positive
        // gap should be impossible. Clamping here rather than substituting an
        // arbitrary 0.5s keeps the guarantee explicit if that ever changes.
        if (timeGapSeconds <= 0) {
            timeGapSeconds = 1.0;
        }
        if (gpsConfidence < 0) {
            gpsConfidence = 0;
        } else if (gpsConfidence > 1) {
            gpsConfidence = 1;
        }
    }
}
