package com.glivt.command;

import com.glivt.device.Device;
import com.glivt.position.DeviceState;
import com.glivt.support.ApiTestSupport;
import com.glivt.tenant.Tenant;
import com.glivt.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A running vehicle that is cut or locked must stop reporting as RUNNING, which
 * is what the fleet map and live-track screens read.
 */
@Transactional
class DeviceImmobilisationTest extends ApiTestSupport {

    private String sendCommand(String token, Long deviceId, String type, boolean confirmed)
            throws Exception {
        return mockMvc.perform(post("/api/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId,
                                "commandType", type,
                                "confirmed", confirmed,
                                "idempotencyKey", deviceId + "-" + type + "-" + System.nanoTime()))))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void engineCutMovesRunningDeviceToImmobilisedWithZeroSpeed() throws Exception {
        Tenant t = seedTenant("IMB");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device device = seedDevice(t.getId(), "930000000000001", DeviceState.RUNNING);
        String token = accessToken("IMB", "admin");

        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.speed").value(40.0));

        sendCommand(token, device.getId(), "ENGINE_CUT", true);

        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("IMMOBILISED"))
                .andExpect(jsonPath("$.data.speed").value(0.0))
                .andExpect(jsonPath("$.data.immobilised").value(true))
                .andExpect(jsonPath("$.data.lastCommandType").value("ENGINE_CUT"));
    }

    @Test
    void engineRestoreReturnsDeviceToTelemetryDerivedState() throws Exception {
        Tenant t = seedTenant("IMR");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device device = seedDevice(t.getId(), "930000000000002", DeviceState.RUNNING);
        String token = accessToken("IMR", "admin");

        sendCommand(token, device.getId(), "ENGINE_CUT", true);
        sendCommand(token, device.getId(), "ENGINE_RESTORE", true);

        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.speed").value(40.0))
                .andExpect(jsonPath("$.data.immobilised").value(false));
    }

    @Test
    void lockImmobilisesAndUnlockClearsIt() throws Exception {
        Tenant t = seedTenant("LCK");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device device = seedDevice(t.getId(), "930000000000003", DeviceState.RUNNING);
        String token = accessToken("LCK", "admin");

        sendCommand(token, device.getId(), "LOCK", true);
        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.state").value("IMMOBILISED"))
                .andExpect(jsonPath("$.data.locked").value(true));

        sendCommand(token, device.getId(), "UNLOCK", true);
        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    void nonStateChangingCommandLeavesDeviceRunningAndStaysRequested() throws Exception {
        Tenant t = seedTenant("LOC");
        seedUser(t.getId(), "admin", Role.ADMIN);
        Device device = seedDevice(t.getId(), "930000000000004", DeviceState.RUNNING);
        String token = accessToken("LOC", "admin");

        mockMvc.perform(post("/api/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", device.getId(),
                                "commandType", "REQUEST_LOCATION",
                                "idempotencyKey", device.getId() + "-loc-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        mockMvc.perform(get("/api/devices/" + device.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.immobilised").value(false));
    }
}
