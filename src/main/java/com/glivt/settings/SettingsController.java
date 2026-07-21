package com.glivt.settings;

import com.glivt.common.ApiResponse;
import com.glivt.settings.dto.SettingsDto;
import com.glivt.settings.dto.SettingsRequest;
import com.glivt.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings", description = "Per-user application preferences")
public class SettingsController {

    private final SettingsService service;
    private final CurrentUser currentUser;

    public SettingsController(SettingsService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<SettingsDto> get() {
        var user = currentUser.require();
        return ApiResponse.ok(service.get(user.getTenantId(), user.getUserId()));
    }

    @PutMapping
    public ApiResponse<SettingsDto> update(@Valid @RequestBody SettingsRequest request) {
        var user = currentUser.require();
        return ApiResponse.ok(service.update(user.getTenantId(), user.getUserId(), user.getUsername(), request));
    }
}
