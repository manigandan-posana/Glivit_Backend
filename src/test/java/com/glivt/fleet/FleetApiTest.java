package com.glivt.fleet;

import com.glivt.device.Device;
import com.glivt.position.DeviceState;
import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class FleetApiTest extends ApiTestSupport {

    @Test
    void deviceListIsScopedToTenant() throws Exception {
        Tenant a = seedTenant("AAA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "111111111111111", DeviceState.RUNNING);

        Tenant b = seedTenant("BBB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        seedDevice(b.getId(), "222222222222222", DeviceState.STOPPED);

        String token = accessToken("AAA", "admin");

        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].imei").value("111111111111111"));
    }

    @Test
    void cannotReadAnotherTenantsDeviceById() throws Exception {
        Tenant a = seedTenant("AAA");
        seedUser(a.getId(), "admin", Role.ADMIN);

        Tenant b = seedTenant("BBB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        Device otherDevice = seedDevice(b.getId(), "222222222222222", DeviceState.STOPPED);

        String token = accessToken("AAA", "admin");

        // IDOR attempt: tenant A user requests tenant B device -> 404 (not found in scope).
        mockMvc.perform(get("/api/devices/" + otherDevice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void driverCannotListAllDevices() throws Exception {
        Tenant a = seedTenant("AAA");
        seedUser(a.getId(), "driver", Role.DRIVER);
        seedDevice(a.getId(), "111111111111111", DeviceState.RUNNING);

        String token = accessToken("AAA", "driver");

        // Driver lacks view_all_vehicles -> forbidden, not an empty list.
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void dashboardSummaryReflectsStateCounts() throws Exception {
        Tenant a = seedTenant("AAA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "111111111111111", DeviceState.RUNNING);
        seedDevice(a.getId(), "222222222222222", DeviceState.RUNNING);
        seedDevice(a.getId(), "333333333333333", DeviceState.STOPPED);

        String token = accessToken("AAA", "admin");

        mockMvc.perform(get("/api/dashboard/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.counts.RUNNING").value(2))
                .andExpect(jsonPath("$.data.counts.STOPPED").value(1))
                .andExpect(jsonPath("$.data.counts.IDLE").value(0));
    }

    @Test
    void tenantResolveReturnsBrandingConfig() throws Exception {
        seedTenant("ACME");

        mockMvc.perform(get("/api/tenant/ACME/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyCode").value("ACME"))
                .andExpect(jsonPath("$.data.primaryColor").value("#27D34D"));
    }
}
