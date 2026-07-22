package com.glivt.ai.controller;

import com.glivt.ai.dto.AiDashboardSummaryDto;
import com.glivt.ai.dto.AiEventDto;
import com.glivt.ai.dto.DispatchRecommendRequestDto;
import com.glivt.ai.dto.DispatchRecommendResponseDto;
import com.glivt.ai.dto.DriverScoreDto;
import com.glivt.ai.dto.EtaRequestDto;
import com.glivt.ai.dto.EtaResponseDto;
import com.glivt.ai.dto.FeedbackRequestDto;
import com.glivt.ai.dto.GeofenceSuggestionDto;
import com.glivt.ai.dto.MaintenancePredictionDto;
import com.glivt.ai.service.AiAlertBroadcaster;
import com.glivt.ai.service.AiFleetService;
import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI Fleet Intelligence API. Every endpoint is tenant-scoped via the
 * authenticated principal (never a client-supplied tenant), permission-checked,
 * and resilient: ML/LLM outages degrade to deterministic results rather than
 * failing. Expensive endpoints are rate limited per tenant/user.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Fleet Intelligence", description = "Anomalies, driver scoring, ETA, maintenance, dispatch")
public class AiController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPENSIVE_CALLS_PER_MINUTE = 30;

    private final AiFleetService fleetService;
    private final AiAlertBroadcaster broadcaster;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public AiController(AiFleetService fleetService, AiAlertBroadcaster broadcaster,
                        CurrentUser currentUser, RateLimiter rateLimiter) {
        this.fleetService = fleetService;
        this.broadcaster = broadcaster;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/dashboard")
    public ApiResponse<AiDashboardSummaryDto> dashboard() {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.dashboard(currentUser.tenantId()));
    }

    @GetMapping("/events")
    public ApiResponse<PageResponse<AiEventDto>> events(
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.ok(fleetService.listEvents(currentUser.tenantId(), vehicleId, severity,
                eventType, PageRequest.of(Math.max(page, 0), safeSize)));
    }

    @PostMapping("/events/{id}/acknowledge")
    public ApiResponse<AiEventDto> acknowledge(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_NOTIFICATIONS);
        AppUserPrincipal user = currentUser.require();
        return ApiResponse.ok(fleetService.acknowledge(user.getTenantId(), user.getUserId(),
                user.getUsername(), id));
    }

    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@Valid @RequestBody FeedbackRequestDto request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_NOTIFICATIONS);
        AppUserPrincipal user = currentUser.require();
        fleetService.submitFeedback(user.getTenantId(), user.getUserId(), user.getUsername(), request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/predict/eta")
    public ApiResponse<EtaResponseDto> predictEta(@Valid @RequestBody EtaRequestDto request) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        rateLimit("eta");
        return ApiResponse.ok(fleetService.predictEta(currentUser.tenantId(), request));
    }

    @GetMapping("/scoring/driver/{driverId}")
    public ApiResponse<DriverScoreDto> driverScore(@PathVariable Long driverId) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.driverScore(currentUser.tenantId(), driverId));
    }

    @GetMapping("/geofence/suggestions")
    public ApiResponse<List<GeofenceSuggestionDto>> geofenceSuggestions() {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        return ApiResponse.ok(fleetService.geofenceSuggestions(currentUser.tenantId()));
    }

    @PostMapping("/geofence/suggestions/{id}/approve")
    public ApiResponse<Void> approveGeofence(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        AppUserPrincipal user = currentUser.require();
        fleetService.approveGeofenceSuggestion(user.getTenantId(), user.getUserId(),
                user.getUsername(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/geofence/suggestions/{id}/dismiss")
    public ApiResponse<Void> dismissGeofence(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        AppUserPrincipal user = currentUser.require();
        fleetService.dismissGeofenceSuggestion(user.getTenantId(), user.getUserId(),
                user.getUsername(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/dispatch/recommend")
    public ApiResponse<DispatchRecommendResponseDto> dispatch(
            @Valid @RequestBody DispatchRecommendRequestDto request) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        rateLimit("dispatch");
        AppUserPrincipal user = currentUser.require();
        return ApiResponse.ok(fleetService.dispatchRecommend(user.getTenantId(), user.getUserId(),
                user.getUsername(), request));
    }

    @GetMapping("/maintenance/predict/{deviceId}")
    public ApiResponse<List<MaintenancePredictionDto>> maintenance(@PathVariable Long deviceId) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.maintenanceForDevice(currentUser.tenantId(), deviceId));
    }

    @GetMapping("/maintenance")
    public ApiResponse<List<MaintenancePredictionDto>> fleetMaintenance() {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.fleetMaintenance(currentUser.tenantId()));
    }

    /**
     * Server-Sent Events stream of AI alerts for the caller's tenant. The stream
     * is tenant-scoped at subscription time so a client only ever receives its
     * own tenant's events.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return broadcaster.subscribe(currentUser.tenantId());
    }

    private void rateLimit(String bucket) {
        AppUserPrincipal user = currentUser.require();
        rateLimiter.check("ai:" + bucket + ":" + user.getTenantId() + ":" + user.getUserId(),
                EXPENSIVE_CALLS_PER_MINUTE, Duration.ofMinutes(1));
    }
}
