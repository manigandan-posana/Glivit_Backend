package com.glivt.audit;

import com.glivt.audit.dto.AuditDto;
import com.glivt.common.ApiResponse;
import com.glivt.common.PageResponse;
import com.glivt.security.CurrentUser;
import com.glivt.security.PermissionKeys;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Sensitive operation audit trail")
public class AuditController {

    private final AuditLogRepository repository;
    private final CurrentUser currentUser;

    public AuditController(AuditLogRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PageResponse<AuditDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser.requirePermission(PermissionKeys.VIEW_AUDIT_LOGS);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(PageResponse.from(
                repository.findByTenantIdOrderByCreatedAtDesc(currentUser.tenantId(),
                        PageRequest.of(Math.max(page, 0), safeSize,
                                Sort.by(Sort.Direction.DESC, "createdAt"))),
                AuditDto::from));
    }
}
