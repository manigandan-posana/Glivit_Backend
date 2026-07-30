package com.glivt.tenant;

import com.glivt.device.Device;
import com.glivt.position.DeviceState;
import com.glivt.support.ApiTestSupport;
import com.glivt.user.Role;
import com.glivt.user.User;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant data isolation, exercised through the HTTP API rather than the
 * services, because the guarantee that matters is the one a client can observe.
 *
 * <p>Every test here is written from the attacker's point of view: hold a valid
 * session for tenant A and try to see or reach tenant B.
 */
@Transactional
class TenantIsolationTest extends ApiTestSupport {

    // ------------------------------------------------------------------
    // Reading another tenant's data
    // ------------------------------------------------------------------

    @Test
    void devicesAndUsersNeverCrossTenants() throws Exception {
        Tenant a = seedTenant("ISOA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "700000000000001", DeviceState.RUNNING);

        Tenant b = seedTenant("ISOB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        seedUser(b.getId(), "b-only-user", Role.ADMIN);
        seedDevice(b.getId(), "700000000000002", DeviceState.STOPPED);

        String tokenA = accessToken("ISOA", "admin");

        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].imei").value("700000000000001"));

        // Tenant A sees exactly its own single user, never tenant B's extra account.
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("admin"));
    }

    @Test
    void geofencesAlertsReportsAndSettingsNeverCrossTenants() throws Exception {
        Tenant a = seedTenant("GEOA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant b = seedTenant("GEOB");
        seedUser(b.getId(), "admin", Role.ADMIN);

        String tokenB = accessToken("GEOB", "admin");
        // Tenant B creates a geofence with a name tenant A will also use.
        mockMvc.perform(post("/api/geofences")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Shared Depot Name",
                                "type", "CIRCLE",
                                "coordinates", List.of(List.of(77.59, 12.97)),
                                "radiusMeters", 300))))
                .andExpect(status().isCreated());

        String tokenA = accessToken("GEOA", "admin");

        // A cannot see B's geofence...
        mockMvc.perform(get("/api/geofences").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        // ...and the name is free inside A, proving the unique key is tenant-scoped
        // and not a channel for probing another tenant's data.
        mockMvc.perform(post("/api/geofences")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Shared Depot Name",
                                "type", "CIRCLE",
                                "coordinates", List.of(List.of(77.59, 12.97)),
                                "radiusMeters", 300))))
                .andExpect(status().isCreated());

        // Alerts and reports start empty for A regardless of B's activity.
        mockMvc.perform(get("/api/events").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/reports").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void dashboardCountsAreCalculatedPerTenant() throws Exception {
        Tenant a = seedTenant("DSHA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "710000000000001", DeviceState.RUNNING);
        seedDevice(a.getId(), "710000000000002", DeviceState.RUNNING);

        Tenant b = seedTenant("DSHB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        seedDevice(b.getId(), "710000000000003", DeviceState.STOPPED);

        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + accessToken("DSHA", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.counts.RUNNING").value(2))
                .andExpect(jsonPath("$.data.counts.STOPPED").value(0));

        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + accessToken("DSHB", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.counts.STOPPED").value(1))
                .andExpect(jsonPath("$.data.counts.RUNNING").value(0));
    }

    // ------------------------------------------------------------------
    // Direct API manipulation
    // ------------------------------------------------------------------

    @Test
    void spoofedTenantHeaderIsRejected() throws Exception {
        Tenant a = seedTenant("SPFA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant b = seedTenant("SPFB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        seedDevice(b.getId(), "720000000000001", DeviceState.RUNNING);

        String tokenA = accessToken("SPFA", "admin");

        // Claiming tenant B in the request header while holding a tenant A token
        // does not widen access: the request is rejected outright, and it certainly
        // does not return tenant B's fleet.
        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("X-Tenant-Id", String.valueOf(b.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void matchingTenantHeaderIsAccepted() throws Exception {
        Tenant a = seedTenant("HDRA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "730000000000001", DeviceState.RUNNING);

        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer " + accessToken("HDRA", "admin"))
                        .header("X-Tenant-Id", String.valueOf(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void cannotSwitchToAnUnauthorisedTenant() throws Exception {
        Tenant a = seedTenant("SWNA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant b = seedTenant("SWNB");
        seedUser(b.getId(), "admin", Role.ADMIN);

        // A company admin with no grant for tenant B is refused, and the tenant is
        // not even listed for them.
        String tokenA = accessToken("SWNA", "admin");

        mockMvc.perform(post("/api/tenants/" + b.getId() + "/switch")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/tenants").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].tenantId").value("SWNA"));
    }

    @Test
    void cannotSwitchToADisabledTenant() throws Exception {
        Tenant a = seedTenant("DISA");
        User admin = seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant disabled = seedTenant("DISB", TenantStatus.DISABLED);
        grantTenantAccess(disabled.getId(), admin.getId());

        mockMvc.perform(post("/api/tenants/" + disabled.getId() + "/switch")
                        .header("Authorization", "Bearer " + accessToken("DISA", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTenantIdIsRejectedWithoutRevealingItsAbsence() throws Exception {
        Tenant a = seedTenant("UNKA");
        seedUser(a.getId(), "admin", Role.ADMIN);

        mockMvc.perform(post("/api/tenants/99999999/switch")
                        .header("Authorization", "Bearer " + accessToken("UNKA", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Switching
    // ------------------------------------------------------------------

    @Test
    void afterSwitchingOnlyTheNewTenantsDataIsVisible() throws Exception {
        Tenant a = seedTenant("MOVA");
        User admin = seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "740000000000001", DeviceState.RUNNING);

        Tenant b = seedTenant("MOVB");
        seedDevice(b.getId(), "740000000000002", DeviceState.STOPPED);
        seedDevice(b.getId(), "740000000000003", DeviceState.STOPPED);
        grantTenantAccess(b.getId(), admin.getId());

        String tokenA = accessToken("MOVA", "admin");
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        String switched = mockMvc.perform(post("/api/tenants/" + b.getId() + "/switch")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.companyCode").value("MOVB"))
                .andExpect(jsonPath("$.data.activeTenant.current").value(true))
                .andReturn().getResponse().getContentAsString();
        String tokenB = objectMapper.readTree(switched)
                .path("data").path("session").path("accessToken").asString();

        // The new session sees tenant B's fleet and only tenant B's fleet.
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // The switched session's identity reports B as active and A as home.
        mockMvc.perform(get("/api/tenants").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
    }

    @Test
    void switchedSessionSurvivesTokenRefreshWithoutRevertingTenant() throws Exception {
        Tenant a = seedTenant("REFA");
        User admin = seedUser(a.getId(), "admin", Role.ADMIN);
        seedDevice(a.getId(), "750000000000001", DeviceState.RUNNING);

        Tenant b = seedTenant("REFB");
        seedDevice(b.getId(), "750000000000002", DeviceState.STOPPED);
        seedDevice(b.getId(), "750000000000003", DeviceState.STOPPED);
        grantTenantAccess(b.getId(), admin.getId());

        String tokenA = accessToken("REFA", "admin");
        String switched = mockMvc.perform(post("/api/tenants/" + b.getId() + "/switch")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(switched)
                .path("data").path("session").path("refreshToken").asString();

        String refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.tenantId").value(b.getId().intValue()))
                .andExpect(jsonPath("$.data.user.homeTenantId").value(a.getId().intValue()))
                .andReturn().getResponse().getContentAsString();
        String rotated = objectMapper.readTree(refreshed).path("data").path("accessToken").asString();

        // Still tenant B after the rotation - not silently back to the home tenant.
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + rotated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void switchingToTheAlreadyActiveTenantIsRejected() throws Exception {
        Tenant a = seedTenant("SAMA");
        seedUser(a.getId(), "admin", Role.ADMIN);

        mockMvc.perform(post("/api/tenants/" + a.getId() + "/switch")
                        .header("Authorization", "Bearer " + accessToken("SAMA", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokingAGrantLocksTheSwitchedSessionOutImmediately() throws Exception {
        Tenant a = seedTenant("RVKA");
        User admin = seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant b = seedTenant("RVKB");
        seedDevice(b.getId(), "760000000000001", DeviceState.RUNNING);
        grantTenantAccess(b.getId(), admin.getId());

        String switched = mockMvc.perform(post("/api/tenants/" + b.getId() + "/switch")
                        .header("Authorization", "Bearer " + accessToken("RVKA", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenB = objectMapper.readTree(switched)
                .path("data").path("session").path("accessToken").asString();

        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        // The grant is withdrawn while the token is still cryptographically valid.
        tenantUserRepository.findByUserIdAndTenantId(admin.getId(), b.getId())
                .ifPresent(tenantUserRepository::delete);
        tenantUserRepository.flush();

        // Re-authorisation happens per request, so the token stops working at once.
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // GPS ingestion
    // ------------------------------------------------------------------

    @Test
    void gpsPositionsAreStoredUnderTheDevicesOwnTenant() throws Exception {
        Tenant a = seedTenant("GPSA");
        seedUser(a.getId(), "admin", Role.ADMIN);
        Tenant b = seedTenant("GPSB");
        seedUser(b.getId(), "admin", Role.ADMIN);
        Device deviceB = seedDevice(b.getId(), "770000000000002", DeviceState.STOPPED);
        deviceB.setDeviceToken("token-for-b");
        deviceRepository.saveAndFlush(deviceB);

        // The device posts its own position; no tenant is supplied by the caller.
        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imei", deviceB.getImei(),
                                "token", "token-for-b",
                                "latitude", 12.98,
                                "longitude", 77.60,
                                "speed", 32))))
                .andExpect(status().isOk());

        // The reading landed in tenant B and is invisible to tenant A.
        mockMvc.perform(get("/api/devices/" + deviceB.getId() + "/positions")
                        .header("Authorization", "Bearer " + accessToken("GPSA", "admin")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/devices/" + deviceB.getId() + "/positions")
                        .header("Authorization", "Bearer " + accessToken("GPSB", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
