package com.glivt.group;

import com.glivt.common.ApiResponse;
import com.glivt.group.dto.GroupDto;
import com.glivt.group.dto.GroupRequest;
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
@RequestMapping("/api/groups")
@Tag(name = "Groups", description = "Hierarchical device group management")
public class GroupController {

    private final GroupService service;
    private final CurrentUser currentUser;

    public GroupController(GroupService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<GroupDto>> list() {
        currentUser.requirePermission(PermissionKeys.MANAGE_GROUPS);
        return ApiResponse.ok(service.list(currentUser.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GroupDto> create(@Valid @RequestBody GroupRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GROUPS);
        var user = currentUser.require();
        return ApiResponse.ok(service.create(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<GroupDto> update(@PathVariable Long id,
                                        @Valid @RequestBody GroupRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GROUPS);
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_GROUPS);
        var user = currentUser.require();
        service.delete(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }
}
