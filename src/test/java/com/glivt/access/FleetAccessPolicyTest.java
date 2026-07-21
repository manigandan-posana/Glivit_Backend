package com.glivt.access;

import com.glivt.device.Device;
import com.glivt.device.DeviceStatus;
import com.glivt.driver.Driver;
import com.glivt.driver.DriverRepository;
import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import com.glivt.user.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves FleetAccessPolicy closes the driver IDOR: a driver (or project-scoped
 * user) can only reach devices within their assignment scope, and out-of-scope
 * ids return 404 (no enumeration), even within the same tenant.
 */
@Transactional
class FleetAccessPolicyTest extends ApiTestSupport {

    @Autowired private DriverRepository driverRepository;
    @Autowired private VehicleDriverAssignmentRepository assignmentRepository;
    @Autowired private UserProjectAssignmentRepository projectAssignmentRepository;

    private Device newDevice(Long tenantId, String imei, Long vehicleId, Long projectId) {
        Device d = new Device();
        d.setTenantId(tenantId);
        d.setImei(imei);
        d.setName(imei);
        d.setStatus(DeviceStatus.ACTIVE);
        d.setExpiryDate(LocalDate.now().plusMonths(6));
        d.setVehicleId(vehicleId);
        d.setProjectId(projectId);
        return deviceRepository.save(d);
    }

    private void assignVehicleToDriver(Long tenantId, Long vehicleId, Long driverId) {
        VehicleDriverAssignment a = new VehicleDriverAssignment();
        a.setTenantId(tenantId);
        a.setVehicleId(vehicleId);
        a.setDriverId(driverId);
        a.setActive(true);
        assignmentRepository.save(a);
    }

    @Test
    void driverReachesOnlyAssignedDevice() throws Exception {
        Tenant t = seedTenant("ACL");
        User driverUser = seedUser(t.getId(), "driver1", Role.DRIVER);

        long vehicleId = 5001L;
        Device assigned = newDevice(t.getId(), "700000000000001", vehicleId, null);
        Device other = newDevice(t.getId(), "700000000000002", null, null);

        Driver driver = new Driver();
        driver.setTenantId(t.getId());
        driver.setUserId(driverUser.getId());
        driver.setName("Driver One");
        driver = driverRepository.save(driver);
        assignVehicleToDriver(t.getId(), vehicleId, driver.getId());

        String token = accessToken("ACL", "driver1");

        // Assigned device: reachable across detail, positions and playback.
        mockMvc.perform(get("/api/devices/" + assigned.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imei").value("700000000000001"));
        mockMvc.perform(get("/api/devices/" + assigned.getId() + "/positions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/" + assigned.getId() + "/playback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Unassigned device in the SAME tenant: 404 everywhere (no enumeration).
        mockMvc.perform(get("/api/devices/" + other.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/" + other.getId() + "/positions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/" + other.getId() + "/playback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectScopedUserReachesOnlyProjectDevices() throws Exception {
        Tenant t = seedTenant("PRJ");
        User user = seedUser(t.getId(), "projuser", Role.DRIVER);

        long projectId = 8001L;
        Device inProject = newDevice(t.getId(), "700000000000010", null, projectId);
        Device outside = newDevice(t.getId(), "700000000000011", null, 9999L);

        UserProjectAssignment upa = new UserProjectAssignment();
        upa.setTenantId(t.getId());
        upa.setUserId(user.getId());
        upa.setProjectId(projectId);
        projectAssignmentRepository.save(upa);

        String token = accessToken("PRJ", "projuser");

        mockMvc.perform(get("/api/devices/" + inProject.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/" + outside.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminReachesEveryDeviceInTenant() throws Exception {
        Tenant t = seedTenant("ADM");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device d = newDevice(t.getId(), "700000000000020", null, null);

        String token = accessToken("ADM", "admin");
        mockMvc.perform(get("/api/devices/" + d.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
