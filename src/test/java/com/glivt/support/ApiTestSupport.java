package com.glivt.support;

import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantRepository;
import com.glivt.tenant.TenantStatus;
import com.glivt.tenant.TenantUser;
import com.glivt.tenant.TenantUserRepository;
import com.glivt.user.Role;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared @SpringBootTest wiring + seed helpers for API tests. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "Admin@12345";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected TenantUserRepository tenantUserRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected DeviceRepository deviceRepository;
    @Autowired protected DeviceCurrentPositionRepository currentPositionRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    protected Tenant seedTenant(String code) {
        return seedTenant(code, TenantStatus.ACTIVE);
    }

    protected Tenant seedTenant(String code, TenantStatus status) {
        Tenant tenant = new Tenant();
        tenant.setCompanyCode(code);
        tenant.setName(code + " Fleet");
        tenant.setCompanyName(code + " Logistics");
        tenant.setAppName(code + " App");
        tenant.setAdminEmail("admin@" + code.toLowerCase(java.util.Locale.ROOT) + ".test");
        tenant.setAdminName(code + " Admin");
        tenant.setAdminPhone("+911234567890");
        tenant.setStatus(status);
        return tenantRepository.save(tenant);
    }

    protected User seedUser(Long tenantId, String username, Role role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setName(username);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.save(user);
        // Mirrors production: every login gets its home-tenant access grant.
        tenantUserRepository.save(TenantUser.grant(tenantId, user.getId(), true, null));
        return user;
    }

    /** Grants a user explicit access to a tenant other than their own. */
    protected void grantTenantAccess(Long tenantId, Long userId) {
        tenantUserRepository.save(TenantUser.grant(tenantId, userId, false, null));
    }

    protected Device seedDevice(Long tenantId, String imei, DeviceState state) {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setImei(imei);
        device.setName(imei);
        device.setExpiryDate(LocalDate.now().plusMonths(3));
        device = deviceRepository.save(device);

        DeviceCurrentPosition position = new DeviceCurrentPosition();
        position.setDeviceId(device.getId());
        position.setTenantId(tenantId);
        position.setLatitude(12.97);
        position.setLongitude(77.59);
        position.setSpeed(state == DeviceState.RUNNING ? 40 : 0);
        position.setState(state);
        position.setServerTime(Instant.now());
        position.setUpdatedAt(Instant.now());
        currentPositionRepository.save(position);
        return device;
    }

    protected JsonNode login(String companyCode, String username) throws Exception {
        return login(companyCode, username, PASSWORD);
    }

    protected JsonNode login(String companyCode, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyCode", companyCode,
                                "username", username,
                                "password", password))))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected String accessToken(String companyCode, String username) throws Exception {
        return login(companyCode, username).path("data").path("accessToken").asString();
    }

    /** Login helper for accounts provisioned with a password other than {@link #PASSWORD}. */
    protected String accessToken(String companyCode, String username, String password)
            throws Exception {
        return login(companyCode, username, password).path("data").path("accessToken").asString();
    }
}
