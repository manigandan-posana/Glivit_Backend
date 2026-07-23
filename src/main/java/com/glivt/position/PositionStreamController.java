package com.glivt.position;

import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of live vehicle positions for the caller's tenant.
 * Tenant-scoped at subscription time via the authenticated principal (never a
 * client-supplied tenant) and gated by the live-location permission, so a client
 * only ever receives positions it is allowed to see.
 */
@RestController
@RequestMapping("/api/positions")
@Tag(name = "Live Positions", description = "Real-time vehicle position stream")
public class PositionStreamController {

    private final LivePositionBroadcaster broadcaster;
    private final CurrentUser currentUser;

    public PositionStreamController(LivePositionBroadcaster broadcaster, CurrentUser currentUser) {
        this.broadcaster = broadcaster;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        return broadcaster.subscribe(currentUser.tenantId());
    }
}
