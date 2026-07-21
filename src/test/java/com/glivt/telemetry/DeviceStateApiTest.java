package com.glivt.telemetry;

import com.glivt.device.Device;
import com.glivt.device.DeviceStatus;
import com.glivt.position.Position;
import com.glivt.position.PositionRepository;
import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class DeviceStateApiTest extends ApiTestSupport {

    @Autowired
    private PositionRepository positionRepository;

    private Device newDevice(Long tenantId, String imei, DeviceStatus status, LocalDate expiry) {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setImei(imei);
        device.setName(imei);
        device.setStatus(status);
        device.setExpiryDate(expiry);
        return deviceRepository.save(device);
    }

    @Test
    void suspendedDevicesHiddenByDefaultAndShownWithFilter() throws Exception {
        Tenant t = seedTenant("STA");
        seedUser(t.getId(), "admin", Role.ADMIN);
        newDevice(t.getId(), "910000000000001", DeviceStatus.ACTIVE, LocalDate.now().plusMonths(1));
        newDevice(t.getId(), "910000000000002", DeviceStatus.SUSPENDED, LocalDate.now().plusMonths(1));
        String token = accessToken("STA", "admin");

        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/devices?includeSuspended=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void devicePastExpiryDateReportsExpiredState() throws Exception {
        Tenant t = seedTenant("EXP");
        seedUser(t.getId(), "admin", Role.ADMIN);
        // Status still ACTIVE, but the expiry date has passed => effective EXPIRED.
        Device d = newDevice(t.getId(), "910000000000003", DeviceStatus.ACTIVE, LocalDate.now().minusDays(2));
        String token = accessToken("EXP", "admin");

        mockMvc.perform(get("/api/devices/" + d.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("EXPIRED"));
    }

    @Test
    void dashboardCountsDeviceWithoutAnyPositionAsNoData() throws Exception {
        Tenant t = seedTenant("DSH");
        seedUser(t.getId(), "admin", Role.ADMIN);
        newDevice(t.getId(), "910000000000004", DeviceStatus.ACTIVE, LocalDate.now().plusMonths(1));
        String token = accessToken("DSH", "admin");

        mockMvc.perform(get("/api/dashboard/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.counts.NO_DATA").value(1));
    }

    @Test
    void playbackReturnsSimplifiedRouteWithStops() throws Exception {
        Tenant t = seedTenant("PBK");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device d = newDevice(t.getId(), "910000000000005", DeviceStatus.ACTIVE, LocalDate.now().plusMonths(1));

        Instant base = Instant.now().minus(2, ChronoUnit.HOURS);
        // Moving segment then a 10-minute stop.
        savePosition(t.getId(), d.getId(), 12.90, 77.50, 40, base);
        savePosition(t.getId(), d.getId(), 12.91, 77.51, 45, base.plus(2, ChronoUnit.MINUTES));
        savePosition(t.getId(), d.getId(), 12.92, 77.52, 0, base.plus(5, ChronoUnit.MINUTES));
        savePosition(t.getId(), d.getId(), 12.92, 77.52, 0, base.plus(16, ChronoUnit.MINUTES));

        String token = accessToken("PBK", "admin");
        mockMvc.perform(get("/api/devices/" + d.getId() + "/playback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPoints").value(4))
                .andExpect(jsonPath("$.data.stops[0].minutes").value(11));
    }

    private void savePosition(Long tenantId, Long deviceId, double lat, double lon,
                              double speed, Instant time) {
        Position p = new Position();
        p.setTenantId(tenantId);
        p.setDeviceId(deviceId);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setSpeed(speed);
        p.setCourse(0);
        p.setDeviceTime(time);
        p.setServerTime(time);
        p.setGpsValid(true);
        positionRepository.save(p);
    }
}
