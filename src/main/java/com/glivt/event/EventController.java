package com.glivt.event;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.event.dto.EventCreateRequest;
import com.glivt.event.dto.EventDto;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Alerts, sensor events and acknowledgements")
public class EventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventService service;
    private final CurrentUser currentUser;

    public EventController(EventService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PageResponse<EventDto>> list(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Instant fromTime,
            @RequestParam(required = false) Instant toTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.ok(service.list(currentUser.tenantId(), deviceId, eventType, severity,
                fromTime, toTime, PageRequest.of(Math.max(page, 0), safeSize,
                        Sort.by(Sort.Direction.DESC, "serverTime"))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventDto> create(@Valid @RequestBody EventCreateRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_NOTIFICATIONS);
        return ApiResponse.ok(service.create(currentUser.tenantId(), request));
    }

    @PatchMapping("/{id}/acknowledge")
    public ApiResponse<EventDto> acknowledge(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_NOTIFICATIONS);
        var user = currentUser.require();
        return ApiResponse.ok(service.acknowledge(user.getTenantId(), user.getUserId(),
                user.getUsername(), id));
    }
}
