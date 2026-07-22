package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.glivt.ai.dto.GpsFeatures;
import com.glivt.ai.service.GpsFeatureService;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GpsFeatureServiceTest {

    private final GpsFeatureService service = new GpsFeatureService();

    @Test
    void rejectsInvalidCoordinates() {
        assertThat(service.coordinateValid(200, 10)).isFalse();
        assertThat(service.coordinateValid(10, 200)).isFalse();
        assertThat(service.coordinateValid(0, 0)).isFalse(); // null island
        assertThat(service.coordinateValid(12.97, 77.59)).isTrue();
    }

    @Test
    void firstPointHasNoDerivedMotion() {
        GpsFeatures f = service.compute(null, 12.97, 77.59, 40, 90, 5.0,
                Instant.now(), Instant.now());
        assertThat(f.distanceFromPreviousMeters()).isZero();
        assertThat(f.calculatedSpeedKph()).isZero();
        assertThat(f.coordinateValid()).isTrue();
    }

    @Test
    void computesDistanceAndSpeedBetweenPoints() {
        Instant t0 = Instant.parse("2026-07-22T10:00:00Z");
        DeviceCurrentPosition prev = current(12.9700, 77.5900, 0, 90, t0);
        // ~1.5 km east over 60s => ~90 km/h calculated.
        GpsFeatures f = service.compute(prev, 12.9700, 77.6038, 88, 90, 5.0,
                t0.plusSeconds(60), t0.plusSeconds(60));
        assertThat(f.distanceFromPreviousMeters()).isBetween(1400.0, 1600.0);
        assertThat(f.calculatedSpeedKph()).isBetween(80.0, 100.0);
        assertThat(f.duplicate()).isFalse();
        assertThat(f.outOfOrder()).isFalse();
    }

    @Test
    void impossibleJumpLowersConfidence() {
        Instant t0 = Instant.parse("2026-07-22T10:00:00Z");
        DeviceCurrentPosition prev = current(12.97, 77.59, 0, 0, t0);
        // ~11 km in 10s — physically impossible.
        GpsFeatures f = service.compute(prev, 13.07, 77.59, 5, 0, 5.0,
                t0.plusSeconds(10), t0.plusSeconds(10));
        assertThat(f.distanceFromPreviousMeters()).isGreaterThan(2000);
        assertThat(f.gpsConfidence()).isLessThan(0.6);
    }

    @Test
    void detectsDuplicatePackets() {
        Instant t0 = Instant.parse("2026-07-22T10:00:00Z");
        DeviceCurrentPosition prev = current(12.97, 77.59, 0, 0, t0);
        GpsFeatures f = service.compute(prev, 12.97, 77.59, 0, 0, 5.0, t0, t0);
        assertThat(f.duplicate()).isTrue();
    }

    @Test
    void detectsOutOfOrderPackets() {
        Instant t0 = Instant.parse("2026-07-22T10:00:00Z");
        DeviceCurrentPosition prev = current(12.97, 77.59, 0, 0, t0);
        GpsFeatures f = service.compute(prev, 12.98, 77.60, 30, 45, 5.0,
                t0.minusSeconds(30), t0);
        assertThat(f.outOfOrder()).isTrue();
    }

    private static DeviceCurrentPosition current(double lat, double lng, double speed, double course, Instant t) {
        DeviceCurrentPosition p = new DeviceCurrentPosition();
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setSpeed(speed);
        p.setCourse(course);
        p.setDeviceTime(t);
        p.setState(DeviceState.RUNNING);
        return p;
    }
}
