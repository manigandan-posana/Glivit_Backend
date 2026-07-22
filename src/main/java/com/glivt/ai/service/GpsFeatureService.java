package com.glivt.ai.service;

import com.glivt.ai.dto.GpsFeatures;
import com.glivt.position.DeviceCurrentPosition;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Pure, deterministic GPS feature calculator. Given the previous stored snapshot
 * and the freshly received point, it computes distance, elapsed time, calculated
 * speed, heading change, acceleration and a GPS-confidence score. All safety
 * evidence originates here — never from the language model.
 */
@Service
public class GpsFeatureService {

    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final double MAX_FUTURE_SKEW_SECONDS = 24 * 3600;

    public boolean coordinateValid(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            return false;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }
        // Reject the "null island" 0,0 fix that trackers emit before a lock.
        return !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6);
    }

    public GpsFeatures compute(DeviceCurrentPosition previous, double lat, double lng,
                               double deviceSpeedKph, double heading, Double accuracyMeters,
                               Instant recordedAt, Instant receivedAt) {
        boolean coordinateValid = coordinateValid(lat, lng);
        double dataDelay = Math.max(0, seconds(recordedAt, receivedAt));
        boolean timestampValid = recordedAt != null
                && seconds(receivedAt, recordedAt) <= MAX_FUTURE_SKEW_SECONDS;

        if (previous == null || previous.getDeviceTime() == null) {
            return GpsFeatures.firstPoint(dataDelay, coordinateValid, timestampValid);
        }

        double distance = haversineMeters(previous.getLatitude(), previous.getLongitude(), lat, lng);
        double dt = seconds(previous.getDeviceTime(), recordedAt);
        boolean outOfOrder = dt < 0;
        boolean duplicate = distance < 1.0 && Math.abs(dt) < 1.0;

        double safeDt = Math.abs(dt) < 0.5 ? 0.5 : Math.abs(dt);
        double calcSpeedKph = (distance / safeDt) * 3.6;

        double headingChange = angularDelta(previous.getCourse(), heading);
        double prevSpeedMs = previous.getSpeed() / 3.6;
        double calcSpeedMs = calcSpeedKph / 3.6;
        double acceleration = (calcSpeedMs - prevSpeedMs) / safeDt;

        double stationary = calcSpeedKph < 3.0 ? safeDt : 0.0;

        double confidence = confidence(accuracyMeters, calcSpeedKph, deviceSpeedKph, distance, dt,
                coordinateValid, timestampValid);

        return new GpsFeatures(distance, dt, calcSpeedKph, headingChange, acceleration, stationary,
                dataDelay, confidence, coordinateValid, timestampValid, duplicate, outOfOrder);
    }

    private static double confidence(Double accuracyMeters, double calcSpeedKph, double deviceSpeedKph,
                                     double distance, double dt, boolean coordinateValid,
                                     boolean timestampValid) {
        if (!coordinateValid || !timestampValid) {
            return 0.1;
        }
        double c = 1.0;
        if (accuracyMeters != null) {
            if (accuracyMeters > 100) c -= 0.4;
            else if (accuracyMeters > 30) c -= 0.15;
        }
        double speedMismatch = Math.abs(calcSpeedKph - deviceSpeedKph);
        if (speedMismatch > 40) c -= 0.3;
        else if (speedMismatch > 20) c -= 0.1;
        if (distance > 2000 && dt > 0 && dt < 60) c -= 0.4; // impossible jump
        return Math.max(0.05, Math.min(1.0, c));
    }

    private static double seconds(Instant from, Instant to) {
        if (from == null || to == null) {
            return 0;
        }
        return (to.toEpochMilli() - from.toEpochMilli()) / 1000.0;
    }

    private static double angularDelta(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
