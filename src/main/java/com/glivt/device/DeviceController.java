package com.glivt.device;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.device.dto.DeviceDetail;
import com.glivt.device.dto.DeviceSummary;
import com.glivt.device.dto.DeviceUpsertRequest;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@Tag(name = "Devices", description = "GPS device list and profile")
public class DeviceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeviceService deviceService;
    private final CurrentUser currentUser;
    private final com.glivt.access.FleetAccessPolicy fleetAccessPolicy;

    public DeviceController(DeviceService deviceService, CurrentUser currentUser,
                            com.glivt.access.FleetAccessPolicy fleetAccessPolicy) {
        this.deviceService = deviceService;
        this.currentUser = currentUser;
        this.fleetAccessPolicy = fleetAccessPolicy;
    }

    @GetMapping
    @Operation(summary = "Paginated, tenant-scoped device list with search and filters")
    public ApiResponse<PageResponse<DeviceSummary>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "false") boolean includeSuspended,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.VIEW_ALL_VEHICLES);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.ASC, "name"));
        var scope = fleetAccessPolicy.deviceScope(currentUser.require());
        return ApiResponse.ok(deviceService.list(
                currentUser.tenantId(), projectId, groupId, search, includeSuspended, scope, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Device profile (tenant-scoped, prevents IDOR)")
    public ApiResponse<DeviceDetail> get(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.VIEW_LIVE_LOCATION);
        // Enforce assignment scope, not just tenant scope (prevents driver IDOR).
        fleetAccessPolicy.requireDeviceAccess(currentUser.require(), id);
        return ApiResponse.ok(deviceService.get(currentUser.tenantId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a GPS device and optional vehicle assignment")
    public ApiResponse<DeviceDetail> create(@Valid @RequestBody DeviceUpsertRequest request) {
        currentUser.requirePermission(PermissionKeys.CREATE_DEVICE);
        var user = currentUser.require();
        return ApiResponse.ok(deviceService.create(
                user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a GPS device profile")
    public ApiResponse<DeviceDetail> update(@PathVariable Long id,
                                            @Valid @RequestBody DeviceUpsertRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_DEVICES);
        var user = currentUser.require();
        return ApiResponse.ok(deviceService.update(
                user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft delete a GPS device while preserving position history")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.DELETE_DEVICE);
        var user = currentUser.require();
        deviceService.suspend(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/ingest-token")
    @Operation(summary = "Issue/rotate the device's GPS ingestion token (returned once)")
    public ApiResponse<DeviceIngestTokenResponse> rotateIngestToken(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_DEVICES);
        var user = currentUser.require();
        String token = deviceService.rotateIngestToken(
                user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(new DeviceIngestTokenResponse(id, token));
    }

    public record DeviceIngestTokenResponse(Long deviceId, String ingestToken) {
    }
}
