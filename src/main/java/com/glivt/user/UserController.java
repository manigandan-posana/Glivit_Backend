package com.glivt.user;

import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import com.glivt.user.dto.UserDto;
import com.glivt.user.dto.UserUpsertRequest;
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
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Tenant-scoped users, roles and granular permissions")
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService service;
    private final CurrentUser currentUser;

    public UserController(UserService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.MANAGE_USERS);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.ok(service.list(currentUser.tenantId(), search,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name"))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserDto> create(@Valid @RequestBody UserUpsertRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_USERS);
        var user = currentUser.require();
        return ApiResponse.ok(service.create(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserDto> update(@PathVariable Long id,
                                       @Valid @RequestBody UserUpsertRequest request) {
        currentUser.requirePermission(PermissionKeys.MANAGE_USERS);
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        currentUser.requirePermission(PermissionKeys.MANAGE_USERS);
        var user = currentUser.require();
        service.disable(user.getTenantId(), user.getUserId(), user.getUsername(), id);
        return ApiResponse.ok(null);
    }
}
