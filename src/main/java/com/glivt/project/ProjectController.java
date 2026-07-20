package com.glivt.project;

import com.glivt.common.ApiResponse;
import com.glivt.project.dto.ProjectDto;
import com.glivt.project.dto.ProjectRequest;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Tenant-scoped project management")
public class ProjectController {

    private final ProjectService service;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List projects available to the tenant")
    public ApiResponse<List<ProjectDto>> list() {
        currentUser.requirePermission(PermissionKeys.MANAGE_PROJECTS);
        return ApiResponse.ok(service.list(currentUser.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectDto> create(@Valid @RequestBody ProjectRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_PROJECTS);
        var user = currentUser.require();
        return ApiResponse.ok(service.create(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectDto> update(@PathVariable Long id,
                                          @Valid @RequestBody ProjectRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_PROJECTS);
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_PROJECTS);
        var user = currentUser.require();
        service.disable(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }
}
