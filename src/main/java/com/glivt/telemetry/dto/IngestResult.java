package com.glivt.telemetry.dto;

import com.glivt.position.DeviceState;

/** Ingestion outcome returned to the device / gateway (no secrets echoed). */
public record IngestResult(
        long deviceId,
        int accepted,
        int duplicates,
        int rejected,
        DeviceState state) {
}
