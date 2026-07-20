package com.glivt.driver;

import com.glivt.common.ApiResponse;
import com.glivt.driver.dto.DriverDto;
import com.glivt.driver.dto.DriverRequest;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers")
@Tag(name = "Drivers", description = "Driver records and assignment metadata")
public class DriverController {

    private final DriverService service;
    private final CurrentUser currentUser;

    public DriverController(DriverService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<DriverDto>> list() {
        currentUser.requirePermission(PermissionKeys.MANAGE_DRIVERS);
        return ApiResponse.ok(service.list(currentUser.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverDto> create(@Valid @RequestBody DriverRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_DRIVERS);
        var user = currentUser.require();
        return ApiResponse.ok(service.create(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DriverDto> update(@PathVariable Long id,
                                         @Valid @RequestBody DriverRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_DRIVERS);
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_DRIVERS);
        var user = currentUser.require();
        service.disable(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }
}
