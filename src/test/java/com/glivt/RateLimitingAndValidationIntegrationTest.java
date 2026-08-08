package com.glivt;

import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.beans.factory.annotation.Autowired;
import com.glivt.security.RateLimitingFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest(properties = "app.rate-limiting.enabled=true")
class RateLimitingAndValidationIntegrationTest extends ApiTestSupport {

    @Autowired
    private RateLimitingFilter rateLimitingFilter;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        rateLimitingFilter.reset();
        tenantRepository.deleteAll();
        userRepository.deleteAll();
        tenantUserRepository.deleteAll();

        tenant = seedTenant("TEST");
        seedUser(tenant.getId(), "testuser", Role.ADMIN);
    }

    @Test
    void testValidLoginPayload() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyCode", "TEST",
                                "username", "testuser",
                                "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void testInvalidLoginPayloadRejected() throws Exception {
        // companyCode is too short (less than 2 characters)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyCode", "T",
                                "username", "testuser",
                                "password", PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLoginRateLimitingEnforced() throws Exception {
        // The limit is 5 requests/minute/IP
        // Let's send 5 valid requests, they should succeed
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "companyCode", "TEST",
                                    "username", "testuser",
                                    "password", PASSWORD))))
                    .andExpect(status().isOk());
        }

        // The 6th request should be rate limited (HTTP 429)
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyCode", "TEST",
                                "username", "testuser",
                                "password", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertThat(content).contains("RATE_LIMITED");
        assertThat(content).contains("Too many requests. Try again later.");
    }
}
