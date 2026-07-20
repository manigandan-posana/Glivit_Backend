package com.glivt.command;

import com.glivt.command.dto.CommandDto;
import com.glivt.command.dto.CommandRequest;
import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/commands")
@Tag(name = "Commands", description = "Safe device command centre with idempotency")
public class CommandController {

    private final CommandService service;
    private final CurrentUser currentUser;

    public CommandController(CommandService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PageResponse<CommandDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.SEND_COMMANDS);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(service.list(currentUser.tenantId(),
                PageRequest.of(Math.max(page, 0), safeSize,
                        Sort.by(Sort.Direction.DESC, "requestedAt"))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommandDto> submit(@Valid @RequestBody CommandRequest request) {
        currentUser.requirePermission(PermissionKeys.SEND_COMMANDS);
        var user = currentUser.require();
        return ApiResponse.ok(service.submit(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }
}
