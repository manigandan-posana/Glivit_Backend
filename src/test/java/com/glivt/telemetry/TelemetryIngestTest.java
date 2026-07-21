package com.glivt.telemetry;

import com.glivt.device.Device;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceState;
import com.glivt.position.PositionRepository;
import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class TelemetryIngestTest extends ApiTestSupport {

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

    private String body(String imei, String token, double lat, double lon,
                        double speed, Boolean ignition, String messageId) throws Exception {
        var map = new java.util.HashMap<String, Object>();
        map.put("imei", imei);
        map.put("token", token);
        map.put("latitude", lat);
        map.put("longitude", lon);
        map.put("speed", speed);
        map.put("ignition", ignition);
        map.put("gpsValid", true);
        if (messageId != null) {
            map.put("messageId", messageId);
        }
        return objectMapper.writeValueAsString(map);
    }

    @Test
    void ingestsPositionAndComputesMovingState() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000001", DeviceStatus.ACTIVE,
                LocalDate.now().plusMonths(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000001", d.getDeviceToken(), 12.97, 77.59, 42, true, "m1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.state").value("RUNNING"));

        assertEquals(1, positionRepository.count());
        DeviceCurrentPosition cp = currentPositionRepository
                .findByDeviceIdAndTenantId(d.getId(), t.getId()).orElseThrow();
        assertEquals(DeviceState.RUNNING, cp.getState());
        assertEquals(12.97, cp.getLatitude());
    }

    @Test
    void stationaryIgnitionOffIsStopped() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000002", DeviceStatus.ACTIVE,
                LocalDate.now().plusMonths(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000002", d.getDeviceToken(), 12.97, 77.59, 0, false, "m1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("STOPPED"));
    }

    @Test
    void unknownDeviceIsRejected() throws Exception {
        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("000000000000000", "nope", 12.97, 77.59, 10, true, "m1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        Tenant t = seedTenant("ING");
        newDevice(t.getId(), "900000000000003", DeviceStatus.ACTIVE, LocalDate.now().plusMonths(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000003", "wrong-token", 12.97, 77.59, 10, true, "m1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedDeviceIsForbidden() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000004", DeviceStatus.SUSPENDED,
                LocalDate.now().plusMonths(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000004", d.getDeviceToken(), 12.97, 77.59, 10, true, "m1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredDeviceIsForbidden() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000005", DeviceStatus.ACTIVE,
                LocalDate.now().minusDays(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000005", d.getDeviceToken(), 12.97, 77.59, 10, true, "m1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void nullIslandCoordinatesAreRejected() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000006", DeviceStatus.ACTIVE,
                LocalDate.now().plusMonths(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("900000000000006", d.getDeviceToken(), 0, 0, 10, true, "m1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePacketIsDeduplicated() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000007", DeviceStatus.ACTIVE,
                LocalDate.now().plusMonths(1));
        String payload = body("900000000000007", d.getDeviceToken(), 12.97, 77.59, 20, true, "dup-1");

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));

        mockMvc.perform(post("/api/telemetry/positions")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(1));

        assertEquals(1, positionRepository.count());
    }

    @Test
    void batchToleratesInvalidRowAndAcceptsValid() throws Exception {
        Tenant t = seedTenant("ING");
        Device d = newDevice(t.getId(), "900000000000008", DeviceStatus.ACTIVE,
                LocalDate.now().plusMonths(1));

        var valid = Map.of("latitude", 12.97, "longitude", 77.59, "speed", 15.0,
                "ignition", true, "gpsValid", true, "messageId", "b1");
        var invalid = Map.of("latitude", 0.0, "longitude", 0.0, "speed", 5.0,
                "ignition", true, "gpsValid", true, "messageId", "b2");
        var batch = Map.of("imei", "900000000000008", "token", d.getDeviceToken(),
                "positions", List.of(valid, invalid));

        mockMvc.perform(post("/api/telemetry/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(1));

        assertTrue(positionRepository.count() >= 1);
    }
}
