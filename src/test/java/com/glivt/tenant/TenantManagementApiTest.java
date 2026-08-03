package com.glivt.tenant;

import com.glivt.position.DeviceState;
import com.glivt.support.ApiTestSupport;
import com.glivt.user.Role;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tenant CRUD: authorisation, validation, provisioning and deletion guards. */
@Transactional
class TenantManagementApiTest extends ApiTestSupport {

    @Autowired private TenantDataInventory dataInventory;

    private Map<String, Object> createPayload(String tenantId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", tenantId + " Fleet Operations");
        body.put("tenantId", tenantId);
        body.put("companyName", tenantId + " Logistics Pvt Ltd");
        body.put("adminName", "Priya Admin");
        body.put("adminEmail", tenantId.toLowerCase() + "-admin@example.com");
        body.put("adminPhone", "+919876543210");
        body.put("status", "ACTIVE");
        return body;
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @Test
    void onlySuperAdminCanCreateATenant() throws Exception {
        Tenant home = seedTenant("PRMA");
        seedUser(home.getId(), "admin", Role.ADMIN);

        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + accessToken("PRMA", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPayload("NEWCO"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void onlySuperAdminCanUpdateOrDeleteATenant() throws Exception {
        Tenant home = seedTenant("PRMB");
        seedUser(home.getId(), "admin", Role.ADMIN);
        Tenant other = seedTenant("PRMC");
        String token = accessToken("PRMB", "admin");

        Map<String, Object> update = Map.of(
                "name", "Renamed", "companyName", "Renamed Ltd", "adminName", "A",
                "adminEmail", "a@example.com", "adminPhone", "+919876543210", "status", "ACTIVE");

        // Own tenant, but still not a platform admin.
        mockMvc.perform(put("/api/tenants/" + home.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/tenants/" + other.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    @Test
    void createdTenantIsProvisionedWithAnAdminAndNoOtherData() throws Exception {
        Tenant home = seedTenant("CRTA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        // Data in the operator's own tenant that must NOT be copied into the new one.
        seedDevice(home.getId(), "780000000000001", DeviceState.RUNNING);

        String token = accessToken("CRTA", "root");

        String created = mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPayload("FRESHCO"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tenantId").exists())
                .andExpect(jsonPath("$.data.companyName").value("FRESHCO Logistics Pvt Ltd"))
                .andExpect(jsonPath("$.data.adminEmail").value("freshco-admin@example.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // Creating a tenant must not switch into it.
                .andExpect(jsonPath("$.data.current").value(false))
                .andReturn().getResponse().getContentAsString();
        long newTenantId = objectMapper.readTree(created).path("data").path("id").asLong();

        // Every tenant-owned category is empty; only the provisioned admin exists.
        TenantDataInventory.Snapshot snapshot = dataInventory.snapshot(newTenantId);
        assertThat(snapshot.total()).isZero();
        assertThat(snapshot.users()).isEqualTo(1);
        assertThat(snapshot.isPristine()).isTrue();

        // Update provisioned admin's password hash in test so token test utility can authenticate
        com.glivt.user.User freshcoAdmin = userRepository.findByTenantIdAndUsernameIgnoreCase(newTenantId, "freshco-admin@example.com").orElseThrow();
        freshcoAdmin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(freshcoAdmin);

        String adminToken = accessToken(String.valueOf(newTenantId), "freshco-admin@example.com");
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/dashboard/summary").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // The operator's own tenant is untouched and still active for them.
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void createRejectsInvalidEmailAndPhone() throws Exception {
        Tenant home = seedTenant("VALA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        String token = accessToken("VALA", "root");

        Map<String, Object> badEmail = createPayload("BADMAIL");
        badEmail.put("adminEmail", "not-an-email");
        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors.adminEmail").exists());

        Map<String, Object> badPhone = createPayload("BADPHONE");
        badPhone.put("adminPhone", "12");
        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPhone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors.adminPhone").exists());
    }

    @Test
    void createRejectsDuplicateNameAndAdminEmail() throws Exception {
        Tenant home = seedTenant("DUPA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        String token = accessToken("DUPA", "root");

        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPayload("ORIGINAL"))))
                .andExpect(status().isCreated());

        // Same tenant name.
        Map<String, Object> sameName = createPayload("SECONDCO");
        sameName.put("name", "ORIGINAL Fleet Operations");
        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameName)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("Tenant name is already in use"));

        // Same admin email.
        Map<String, Object> sameEmail = createPayload("THIRDCO");
        sameEmail.put("adminEmail", "original-admin@example.com");
        mockMvc.perform(post("/api/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameEmail)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("Admin email is already in use"));
    }

    // ------------------------------------------------------------------
    // Listing & search
    // ------------------------------------------------------------------

    @Test
    void superAdminListIsSearchableAndMarksTheActiveTenant() throws Exception {
        Tenant home = seedTenant("LSTA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        seedTenant("LSTB");
        String token = accessToken("LSTA", "root");

        mockMvc.perform(get("/api/tenants").param("search", "LSTB")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].tenantId").value("LSTB"))
                .andExpect(jsonPath("$.data.content[0].current").value(false));

        mockMvc.perform(get("/api/tenants").param("search", "LSTA")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].current").value(true))
                // You can never delete the tenant you are working in.
                .andExpect(jsonPath("$.data.content[0].canDelete").value(false));
    }

    @Test
    void tenantNotVisibleToTheCallerIsReportedAsNotFound() throws Exception {
        Tenant home = seedTenant("HIDA");
        seedUser(home.getId(), "admin", Role.ADMIN);
        Tenant hidden = seedTenant("HIDB");

        // 404 rather than 403: confirming the tenant exists would leak the platform's
        // tenant list to a company admin.
        mockMvc.perform(get("/api/tenants/" + hidden.getId())
                        .header("Authorization", "Bearer " + accessToken("HIDA", "admin")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    @Test
    void updateChangesDetailsAndStatusButNotTheTenantId() throws Exception {
        Tenant home = seedTenant("UPDA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        Tenant target = seedTenant("UPDB");
        String token = accessToken("UPDA", "root");

        Map<String, Object> update = Map.of(
                "name", "Updated Fleet Name",
                "companyName", "Updated Company Ltd",
                "adminName", "New Admin",
                "adminEmail", "new-admin@example.com",
                "adminPhone", "+919000000000",
                "status", "MAINTENANCE");

        mockMvc.perform(put("/api/tenants/" + target.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Fleet Name"))
                .andExpect(jsonPath("$.data.status").value("MAINTENANCE"))
                // The tenant ID is immutable; every tenant-owned row is bound to it.
                .andExpect(jsonPath("$.data.tenantId").value("UPDB"));
    }

    @Test
    void cannotDisableTheTenantYouAreWorkingIn() throws Exception {
        Tenant home = seedTenant("DSBA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);

        Map<String, Object> update = Map.of(
                "name", "DSBA Fleet",
                "companyName", "DSBA Logistics",
                "adminName", "Root",
                "adminEmail", "root@dsba.test",
                "adminPhone", "+919000000000",
                "status", "DISABLED");

        mockMvc.perform(put("/api/tenants/" + home.getId())
                        .header("Authorization", "Bearer " + accessToken("DSBA", "root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    @Test
    void cannotDeleteTheActiveTenant() throws Exception {
        Tenant home = seedTenant("DELA");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);

        mockMvc.perform(delete("/api/tenants/" + home.getId())
                        .header("Authorization", "Bearer " + accessToken("DELA", "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value("You cannot delete the tenant you are currently using"));
    }

    @Test
    void anEmptyTenantIsDeletedWithoutConfirmation() throws Exception {
        Tenant home = seedTenant("DELB");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        Tenant empty = seedTenant("DELC");
        String token = accessToken("DELB", "root");

        mockMvc.perform(delete("/api/tenants/" + empty.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tenants").param("search", "DELC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void deletingATenantWithDataRequiresConfirmationAndThenRemovesItsData() throws Exception {
        Tenant home = seedTenant("DELD");
        seedUser(home.getId(), "root", Role.SUPER_ADMIN);
        Tenant loaded = seedTenant("DELE");
        seedUser(loaded.getId(), "their-admin", Role.ADMIN);
        seedDevice(loaded.getId(), "790000000000001", DeviceState.RUNNING);
        String token = accessToken("DELD", "root");

        // Unconfirmed: refused, and the reason names what would be destroyed.
        mockMvc.perform(delete("/api/tenants/" + loaded.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.containsString("1 GPS devices")));

        // Wrong confirmation string: still refused.
        mockMvc.perform(delete("/api/tenants/" + loaded.getId())
                        .param("confirmTenantId", "WRONG")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // Correct confirmation: the tenant and everything it owned is gone.
        mockMvc.perform(delete("/api/tenants/" + loaded.getId())
                        .param("confirmTenantId", "DELE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(deviceRepository.countByTenantId(loaded.getId())).isZero();
        assertThat(userRepository.countByTenantId(loaded.getId())).isZero();
        assertThat(tenantRepository.findById(loaded.getId())).isEmpty();

        // The deleted tenant's admin can no longer authenticate.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyCode", "DELE",
                                "username", "their-admin",
                                "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }
}
