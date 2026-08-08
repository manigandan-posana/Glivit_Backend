package com.glivt.ai.controller;

import com.glivt.ai.config.AiProperties;
import com.glivt.ai.dto.AiDashboardSummaryDto;
import com.glivt.ai.dto.AiEventDto;
import com.glivt.ai.dto.ChatRequestDto;
import com.glivt.ai.dto.ChatResponseDto;
import com.glivt.ai.dto.DispatchRecommendRequestDto;
import com.glivt.ai.dto.DispatchRecommendResponseDto;
import com.glivt.ai.dto.DriverScoreDto;
import com.glivt.ai.dto.EtaRequestDto;
import com.glivt.ai.dto.EtaResponseDto;
import com.glivt.ai.dto.FeedbackRequestDto;
import com.glivt.ai.dto.GeofenceSuggestionApprovalDto;
import com.glivt.ai.dto.GeofenceSuggestionDto;
import com.glivt.ai.dto.MaintenancePredictionDto;
import com.glivt.ai.dto.SemanticSearchRequestDto;
import com.glivt.ai.dto.SemanticSearchResponseDto;
import com.glivt.ai.service.AiAlertBroadcaster;
import com.glivt.ai.service.AiChatService;
import com.glivt.ai.service.AiDiagnosticsService;
import com.glivt.ai.service.AiFleetService;
import com.glivt.ai.service.AiGovernanceService;
import com.glivt.ai.service.AiSemanticSearchService;
import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.geofence.dto.GeofenceDto;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import com.glivt.user.Role;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * AI Fleet Intelligence API.
 *
 * <p>Every endpoint is tenant-scoped via the authenticated principal (never a
 * client-supplied tenant), permission-checked, and resilient: an AI outage
 * degrades to a deterministic result rather than failing. Expensive endpoints
 * are rate limited per tenant and user.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Fleet Intelligence", description = "Anomalies, driver scoring, ETA, maintenance, dispatch")
public class AiController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiFleetService fleetService;
    private final AiChatService chatService;
    private final AiSemanticSearchService searchService;
    private final AiDiagnosticsService diagnosticsService;
    private final AiGovernanceService governanceService;
    private final AiAlertBroadcaster broadcaster;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;
    private final AiProperties properties;

    public AiController(AiFleetService fleetService,
            AiChatService chatService,
            AiSemanticSearchService searchService,
            AiDiagnosticsService diagnosticsService,
            AiGovernanceService governanceService,
            AiAlertBroadcaster broadcaster,
            CurrentUser currentUser,
            RateLimiter rateLimiter,
            AiProperties properties) {
        this.fleetService = fleetService;
        this.chatService = chatService;
        this.searchService = searchService;
        this.diagnosticsService = diagnosticsService;
        this.governanceService = governanceService;
        this.broadcaster = broadcaster;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
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
        rateLimit("eta", properties.getLimits().getExpensiveRequestsPerMinute());
        return ApiResponse.ok(fleetService.predictEta(currentUser.tenantId(), request));
    }

    @GetMapping("/scoring/driver/{driverId}")
    public ApiResponse<DriverScoreDto> driverScore(@PathVariable Long driverId) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.driverScore(currentUser.tenantId(), driverId));
    }

    /**
     * Every driver in the tenant with their latest score. Backs the driver
     * picker, so no screen has to hard-code a driver id.
     */
    @GetMapping("/scoring/drivers")
    public ApiResponse<List<DriverScoreDto>> driverScoreboard() {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.driverScoreboard(currentUser.tenantId()));
    }

    /** Score history for the trend chart in the command centre. */
    @GetMapping("/scoring/driver/{driverId}/trend")
    public ApiResponse<List<DriverScoreDto>> driverScoreTrend(@PathVariable Long driverId,
            @RequestParam(defaultValue = "14") int days) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return ApiResponse.ok(fleetService.driverScoreTrend(currentUser.tenantId(), driverId,
                Math.min(Math.max(days, 1), 90)));
    }

    @GetMapping("/geofence/suggestions")
    public ApiResponse<List<GeofenceSuggestionDto>> geofenceSuggestions() {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        return ApiResponse.ok(fleetService.geofenceSuggestions(currentUser.tenantId()));
    }

    /** Approve a suggestion, optionally editing the name or radius first. */
    @PostMapping("/geofence/suggestions/{id}/approve")
    public ApiResponse<GeofenceDto> approveGeofence(@PathVariable Long id,
            @RequestBody(required = false) GeofenceSuggestionApprovalDto approval) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        AppUserPrincipal user = currentUser.require();
        return ApiResponse.ok(fleetService.approveGeofenceSuggestion(
                user.getTenantId(), user.getUserId(), user.getUsername(), id,
                approval == null ? null : approval.getName(),
                approval == null ? null : approval.getRadiusMeters()));
    }

    @PostMapping("/geofence/suggestions/{id}/dismiss")
    public ApiResponse<Void> dismissGeofence(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        AppUserPrincipal user = currentUser.require();
        fleetService.dismissGeofenceSuggestion(user.getTenantId(), user.getUserId(),
                user.getUsername(), id);
        return ApiResponse.ok(null);
    }

    /**
     * Ranked dispatch recommendations. This endpoint only recommends - assigning
     * a vehicle is a separate, permission-checked command the user must confirm.
     */
    @PostMapping("/dispatch/recommend")
    public ApiResponse<DispatchRecommendResponseDto> dispatch(
            @Valid @RequestBody DispatchRecommendRequestDto request) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        rateLimit("dispatch", properties.getLimits().getExpensiveRequestsPerMinute());
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

    /** Semantic search across the caller's own AI events, trips, alerts and predictions. */
    @PostMapping("/search")
    public ApiResponse<SemanticSearchResponseDto> search(
            @Valid @RequestBody SemanticSearchRequestDto request) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        rateLimit("search", properties.getLimits().getExpensiveRequestsPerMinute());
        return ApiResponse.ok(searchService.search(currentUser.require(), request));
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

    @PostMapping("/chat")
    public ApiResponse<ChatResponseDto> chat(@Valid @RequestBody ChatRequestDto request) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        rateLimit("chat", properties.getLimits().getChatRequestsPerMinute());
        return ApiResponse.ok(chatService.chat(currentUser.require(), request));
    }

    // ------------------------------------------------------------------
    // Diagnostics - platform operators only
    // ------------------------------------------------------------------

    /**
     * AI stack diagnostics. Restricted to SUPER_ADMIN because it exposes service
     * topology; the internal token itself is never returned, only a fingerprint
     * so two services can be compared.
     */
    @GetMapping("/diagnostics")
    public ApiResponse<Map<String, Object>> diagnostics(
            @RequestParam(defaultValue = "false") boolean refresh) {
        currentUser.requireRole(Role.SUPER_ADMIN);
        return ApiResponse.ok(diagnosticsService.diagnostics(refresh));
    }

    /** Model / prompt / rule evaluation report built from logged operations and feedback. */
    @GetMapping("/diagnostics/evaluation")
    public ApiResponse<Map<String, Object>> evaluationReport(
            @RequestParam(defaultValue = "7") int days) {
        currentUser.requireRole(Role.SUPER_ADMIN);
        return ApiResponse.ok(governanceService.evaluationReport(currentUser.tenantId(),
                Math.min(Math.max(days, 1), 90)));
    }

    private void rateLimit(String bucket, int perMinute) {
        AppUserPrincipal user = currentUser.require();
        rateLimiter.check("ai:" + bucket + ":" + user.getTenantId() + ":" + user.getUserId(),
                perMinute, Duration.ofMinutes(1));
    }
}
