package com.glivt.auth;

import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@TestPropertySource(properties = "app.demo-login.enabled=true")
class AuthDemoLoginApiTest extends ApiTestSupport {

    @Test
    void enabledDemoLoginReturnsSuperAdminTokensWithoutCredentials() throws Exception {
        Tenant tenant = seedTenant("DEMO");
        seedUser(tenant.getId(), "superadmin", Role.SUPER_ADMIN);

        mockMvc.perform(post("/api/auth/demo/super-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value("superadmin"))
                .andExpect(jsonPath("$.data.user.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.user.permissions.manage_tenants").value(true));
    }
}
