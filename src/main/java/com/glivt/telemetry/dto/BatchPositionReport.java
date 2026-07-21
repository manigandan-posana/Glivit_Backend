package com.glivt.telemetry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Batch ingestion payload. Credentials are supplied once at the envelope level;
 * individual positions inherit them.
 */
public record BatchPositionReport(
        String imei,
        String token,
        @NotEmpty(message = "positions must not be empty") @Valid List<PositionReport> positions) {
}
