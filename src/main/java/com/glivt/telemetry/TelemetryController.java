package com.glivt.telemetry;

import com.glivt.common.ApiResponse;
import com.glivt.telemetry.dto.BatchPositionReport;
import com.glivt.telemetry.dto.IngestResult;
import com.glivt.telemetry.dto.PositionReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-facing telemetry ingestion. Authenticated by device IMEI + token
 * (headers or body), <em>not</em> by a user JWT - GPS devices / protocol
 * gateways have no user session. The tenant is resolved from the device.
 */
@RestController
@RequestMapping("/api/telemetry")
@Tag(name = "Telemetry", description = "GPS position ingestion (device-authenticated)")
public class TelemetryController {

    private final TelemetryIngestService ingestService;

    public TelemetryController(TelemetryIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/positions")
    @Operation(summary = "Ingest a single GPS position for the authenticated device")
    public ApiResponse<IngestResult> ingestOne(
            @RequestHeader(value = "X-Device-Imei", required = false) String imeiHeader,
            @RequestHeader(value = "X-Device-Token", required = false) String tokenHeader,
            @Valid @RequestBody PositionReport report) {
        String imei = firstNonBlank(imeiHeader, report.imei());
        String token = firstNonBlank(tokenHeader, report.token());
        return ApiResponse.ok(ingestService.ingest(imei, token, List.of(report), true));
    }

    @PostMapping("/batch")
    @Operation(summary = "Ingest a batch of GPS positions for the authenticated device")
    public ApiResponse<IngestResult> ingestBatch(
            @RequestHeader(value = "X-Device-Imei", required = false) String imeiHeader,
            @RequestHeader(value = "X-Device-Token", required = false) String tokenHeader,
            @Valid @RequestBody BatchPositionReport batch) {
        String imei = firstNonBlank(imeiHeader, batch.imei());
        String token = firstNonBlank(tokenHeader, batch.token());
        return ApiResponse.ok(ingestService.ingest(imei, token, batch.positions(), false));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
