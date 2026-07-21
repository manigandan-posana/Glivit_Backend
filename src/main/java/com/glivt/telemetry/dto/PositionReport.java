package com.glivt.telemetry.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * A single GPS position report from a device / protocol gateway.
 *
 * <p>Credentials ({@code imei}, {@code token}) may travel in the body (batch
 * mode) or in headers (single mode). The tenant is <em>never</em> supplied by
 * the caller - it is resolved from the authenticated device.
 */
public record PositionReport(
        String imei,
        String token,

        @NotNull(message = "latitude is required") Double latitude,
        @NotNull(message = "longitude is required") Double longitude,

        Double speed,
        Double course,
        Double altitude,
        Double accuracy,

        Boolean ignition,
        Boolean ac,
        Boolean door,
        Boolean lockState,
        Boolean gpsValid,

        Double battery,
        Double externalPower,
        Integer satellites,
        Integer networkSignal,
        Double odometer,
        Double engineHours,
        Double fuelLevel,

        String eventType,
        String address,

        /** Device-reported time (kept verbatim). Server time is always UTC-now. */
        Instant deviceTime,

        /** Optional idempotency id; a stable fallback is derived when absent. */
        String messageId) {
}
