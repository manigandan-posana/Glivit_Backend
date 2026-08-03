package com.glivt.auth;

import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@ActiveProfiles("prod")
@TestPropertySource(properties = "app.demo-login.enabled=true")
class AuthDemoLoginProductionApiTest extends ApiTestSupport {

    @Test
    void productionProfileDisablesDemoLoginEvenWhenFlagIsEnabled() throws Exception {
        Tenant tenant = seedTenant("DEMO");
        seedUser(tenant.getId(), "superadmin", Role.SUPER_ADMIN);

        mockMvc.perform(post("/api/auth/demo/super-admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
