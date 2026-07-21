package com.glivt.position;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.position.dto.PlaybackResponse;
import com.glivt.position.dto.PositionDto;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped position history and route playback for a device. */
@RestController
@RequestMapping("/api/devices/{deviceId}")
@Tag(name = "Positions", description = "Device position history and playback")
public class PositionController {

    private static final int MAX_PAGE_SIZE = 500;

    private final PositionQueryService positionQueryService;
    private final CurrentUser currentUser;

    public PositionController(PositionQueryService positionQueryService, CurrentUser currentUser) {
        this.positionQueryService = positionQueryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/positions")
    @Operation(summary = "Paginated position history for a device (bounded date range)")
    public ApiResponse<PageResponse<PositionDto>> positions(
            @PathVariable Long deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "deviceTime"));
        return ApiResponse.ok(positionQueryService.history(
                currentUser.tenantId(), deviceId, from, to, pageable));
    }

    @GetMapping("/playback")
    @Operation(summary = "Simplified route playback with event and stop markers")
    public ApiResponse<PlaybackResponse> playback(
            @PathVariable Long deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(positionQueryService.playback(
                currentUser.tenantId(), deviceId, from, to));
    }
}
