package com.glivt.ingest;

import com.glivt.common.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-facing GPS ingestion. Authenticated by the per-device X-Device-Token
 * header (NOT a user JWT); the tenant is resolved from the token server-side.
 * This path is outside the JWT filter but every request is still authenticated
 * by the token inside {@link PositionIngestService}.
 */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "GPS Ingestion", description = "Device-authenticated position ingestion")
public class IngestController {

    private final PositionIngestService ingestService;

    public IngestController(PositionIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/positions")
    public ApiResponse<IngestResult> ingest(
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            @Valid @RequestBody IngestPositionRequest request) {
        return ApiResponse.ok(ingestService.ingest(deviceToken, request));
    }
}
