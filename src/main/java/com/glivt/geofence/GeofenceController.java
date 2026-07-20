package com.glivt.geofence;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.geofence.dto.GeofenceDto;
import com.glivt.geofence.dto.GeofenceRequest;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geofences")
@Tag(name = "Geofences", description = "Circle, polygon and route corridor geofences")
public class GeofenceController {

    private final GeofenceService service;
    private final CurrentUser currentUser;

    public GeofenceController(GeofenceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PageResponse<GeofenceDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(service.list(currentUser.tenantId(),
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name"))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GeofenceDto> create(@Valid @RequestBody GeofenceRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        var user = currentUser.require();
        return ApiResponse.ok(service.create(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<GeofenceDto> update(@PathVariable Long id,
                                           @Valid @RequestBody GeofenceRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GEOFENCES);
        var user = currentUser.require();
        service.delete(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }
}
