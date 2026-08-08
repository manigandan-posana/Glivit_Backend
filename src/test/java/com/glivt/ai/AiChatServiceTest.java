package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.glivt.ai.client.AiErrorCode;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.config.AiProperties;
import com.glivt.ai.dto.ChatMessageDto;
import com.glivt.ai.dto.ChatRequestDto;
import com.glivt.ai.dto.ChatResponseDto;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.service.AiChatService;
import com.glivt.ai.service.AiGovernanceService;
import com.glivt.ai.service.ChatIntent;
import com.glivt.ai.service.FleetContextBuilder;
import com.glivt.ai.tools.AiToolRegistry;
import com.glivt.common.exception.BadRequestException;
import com.glivt.event.EventRepository;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.Permissions;
import com.glivt.user.Role;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.DriverRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Chat must be grounded in tenant-scoped fleet data, must degrade to a useful
 * deterministic answer instead of "AI unavailable", and must never let a
 * client-supplied vehicle id cross a tenant boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiChatServiceTest {

    private static final Long TENANT = 1L;

    @Mock private PythonAiClient pythonAiClient;
    @Mock private FleetContextBuilder contextBuilder;
    @Mock private AiEventRepository aiEventRepository;
    @Mock private EventRepository eventRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DriverRepository driverRepository;
    @Mock private AiGovernanceService governanceService;
    @Mock private AiToolRegistry toolRegistry;

    private AiProperties properties;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getPythonService().setToken("secret");
        service = new AiChatService(pythonAiClient, properties, contextBuilder, aiEventRepository,
                eventRepository, vehicleRepository, deviceRepository, driverRepository, governanceService, toolRegistry);
        // No tools advertised: these tests cover the single-turn path. The tool
        // loop itself is covered by AiToolRegistryTest and the live smoke test.
        when(toolRegistry.schemasFor(any())).thenReturn(List.of());

        when(contextBuilder.build(any(), any(), any(), any())).thenReturn(
                new FleetContextBuilder.FleetContext(
                        Map.of("fleetTotals", Map.of("vehicles", 6L)),
                        List.of(new FleetContextBuilder.Citation("VEHICLE", 12L, "TN20CM7677")),
                        "3 of your 6 vehicles are running."));
    }

    private AppUserPrincipal user() {
        return new AppUserPrincipal(9L, TENANT, TENANT, "operator", Role.ADMIN, true,
                Permissions.forUser(Role.ADMIN, null));
    }

    @Test
    void routesThroughThePythonServiceAndReturnsCitations() {
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any())).thenReturn(AiResult.ok(Map.of(
                "reply", "Three vehicles are running.",
                "source", "OLLAMA",
                "mode", "FULL_AI",
                "model", "qwen3.5:2b",
                "promptVersion", "chat-v2"), 900L));

        ChatResponseDto response = service.chat(user(),
                new ChatRequestDto("How many vehicles are running?", null, null, null));

        assertThat(response.reply()).isEqualTo("Three vehicles are running.");
        assertThat(response.source()).isEqualTo("OLLAMA");
        assertThat(response.mode()).isEqualTo("FULL_AI");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).label()).isEqualTo("TN20CM7677");
        // The assistant never proposes an action it could execute itself.
        assertThat(response.suggestedActions()).isEmpty();
    }

    @Test
    void sendsTenantScopedContextAndDetectedIntentButNoRawSql() {
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.ok(Map.of("reply", "ok", "source", "OLLAMA", "mode", "FULL_AI"), 10L));

        service.chat(user(), new ChatRequestDto("How many vehicles are running?", null, null, null));

        ArgumentCaptor<Object> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(pythonAiClient).postForMap(eq("/v1/chat"), captor.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();

        assertThat(payload).containsEntry("tenant_id", TENANT);
        assertThat(payload).containsKey("fleet_context");
        assertThat(payload).containsEntry("intent", ChatIntent.FLEET_STATUS.name());
        assertThat(payload).containsEntry("deterministic_answer", "3 of your 6 vehicles are running.");
        // Structured context only - never a query the model could influence.
        assertThat(payload).doesNotContainKey("sql");
    }

    @Test
    void fallsBackToTheDeterministicAnswerWhenTheAiServiceIsDown() {
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.failure(AiErrorCode.CONNECTION_REFUSED, "refused", 5L));

        ChatResponseDto response = service.chat(user(),
                new ChatRequestDto("How many vehicles are running?", null, null, null));

        // A real, data-backed answer - not "AI model unavailable".
        assertThat(response.reply()).isEqualTo("3 of your 6 vehicles are running.");
        assertThat(response.source()).isEqualTo("DETERMINISTIC");
        assertThat(response.mode()).isEqualTo("DEGRADED");
        assertThat(response.fallbackReason()).isEqualTo("NONE");
        assertThat(response.citations()).hasSize(1);
    }

    @Test
    void surfacesATokenMismatchAsItsOwnFallbackReason() {
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.failure(AiErrorCode.UNAUTHORIZED, "token mismatch", 5L));

        ChatResponseDto response = service.chat(user(),
                new ChatRequestDto("fleet status", null, null, null));

        assertThat(response.fallbackReason()).isEqualTo("NONE");
        assertThat(response.mode()).isEqualTo("DEGRADED");
    }

    @Test
    void anEmptyModelReplyFallsBackRatherThanReturningNothing() {
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.ok(Map.of("reply", "  ", "source", "OLLAMA", "mode", "FULL_AI"), 10L));

        ChatResponseDto response = service.chat(user(),
                new ChatRequestDto("fleet status", null, null, null));

        assertThat(response.reply()).isEqualTo("3 of your 6 vehicles are running.");
        assertThat(response.source()).isEqualTo("DETERMINISTIC");
        assertThat(response.fallbackReason()).isEqualTo("NONE");
    }

    @Test
    void anotherTenantsVehicleIdResolvesToNoSelection() {
        when(vehicleRepository.findByIdAndTenantId(999L, TENANT)).thenReturn(Optional.empty());
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.ok(Map.of("reply", "ok", "source", "OLLAMA", "mode", "FULL_AI"), 10L));

        service.chat(user(), new ChatRequestDto("where is it?", null, null, 999L));

        // The context is built with a null vehicle, not the foreign one.
        org.mockito.Mockito.verify(contextBuilder).build(any(), any(), eq(null), any());
    }

    @Test
    void ownVehicleIdIsAccepted() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(12L);
        vehicle.setTenantId(TENANT);
        vehicle.setName("TN20CM7677");
        when(vehicleRepository.findByIdAndTenantId(12L, TENANT)).thenReturn(Optional.of(vehicle));
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.ok(Map.of("reply", "ok", "source", "OLLAMA", "mode", "FULL_AI"), 10L));

        service.chat(user(), new ChatRequestDto("where is it?", null, null, 12L));

        org.mockito.Mockito.verify(contextBuilder).build(any(), any(), eq(12L), any());
    }

    @Test
    void rejectsAnOverlongMessage() {
        properties.getLimits().setMaxChatMessageChars(20);

        assertThatThrownBy(() -> service.chat(user(),
                new ChatRequestDto("x".repeat(50), null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("20");
    }

    @Test
    void rejectsABlankMessage() {
        assertThatThrownBy(() -> service.chat(user(), new ChatRequestDto("   ", null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void historyIsTrimmedToTheConfiguredLimit() {
        properties.getLimits().setMaxChatHistoryMessages(3);
        when(pythonAiClient.postForMap(eq("/v1/chat"), any(), any()))
                .thenReturn(AiResult.ok(Map.of("reply", "ok", "source", "OLLAMA", "mode", "FULL_AI"), 10L));

        List<ChatMessageDto> history = List.of(
                new ChatMessageDto("user", "one", null),
                new ChatMessageDto("assistant", "two", null),
                new ChatMessageDto("user", "three", null),
                new ChatMessageDto("assistant", "four", null),
                new ChatMessageDto("user", "five", null));

        service.chat(user(), new ChatRequestDto("and now?", history, null, null));

        ArgumentCaptor<Object> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(pythonAiClient).postForMap(eq("/v1/chat"), captor.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> sent = (List<Map<String, String>>) payload.get("history");

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).containsEntry("content", "three");
    }

    @Test
    void intentDetectionPicksTheMostSpecificMatch() {
        assertThat(ChatIntent.detect("How many vehicles are running?")).isEqualTo(ChatIntent.FLEET_STATUS);
        assertThat(ChatIntent.detect("Where is TN20CM7677?")).isEqualTo(ChatIntent.CURRENT_LOCATION);
        assertThat(ChatIntent.detect("Any recent alerts?")).isEqualTo(ChatIntent.RECENT_ALERTS);
        assertThat(ChatIntent.detect("Which vehicle is due for service?")).isEqualTo(ChatIntent.MAINTENANCE);
        assertThat(ChatIntent.detect("Show me driver safety scores")).isEqualTo(ChatIntent.DRIVER_SAFETY);
        assertThat(ChatIntent.detect("What is the fuel consumption?")).isEqualTo(ChatIntent.FUEL);
        assertThat(ChatIntent.detect("When will it arrive?")).isEqualTo(ChatIntent.ETA);
        assertThat(ChatIntent.detect("Which vehicle should I dispatch?")).isEqualTo(ChatIntent.DISPATCH);
        assertThat(ChatIntent.detect("Give me the weekly report")).isEqualTo(ChatIntent.REPORT_SUMMARY);
        // "How do I ..." is a request for help using the app, not for the data.
        assertThat(ChatIntent.detect("How do I export a report?")).isEqualTo(ChatIntent.APP_HELP);
        assertThat(ChatIntent.detect("zzzz")).isEqualTo(ChatIntent.UNKNOWN);
    }
}
