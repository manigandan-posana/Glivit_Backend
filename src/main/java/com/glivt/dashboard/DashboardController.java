package com.glivt.dashboard;

import com.glivt.common.ApiResponse;
import com.glivt.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Fleet status summary")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService dashboardService, CurrentUser currentUser) {
        this.dashboardService = dashboardService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    @Operation(summary = "Vehicle status totals (Running/Stopped/Idle/Inactive/No Data/Expired)")
    public ApiResponse<DashboardSummary> summary() {
        return ApiResponse.ok(dashboardService.summary(currentUser.tenantId()));
    }
}
