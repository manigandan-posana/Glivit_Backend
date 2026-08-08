package com.glivt.ai.service;

import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.config.AiProperties;
import com.glivt.ai.dto.ChatMessageDto;
import com.glivt.ai.dto.ChatRequestDto;
import com.glivt.ai.dto.ChatResponseDto;
import com.glivt.ai.dto.EventChatContextDto;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.tools.AiToolRegistry;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.event.Event;
import com.glivt.event.EventRepository;
import com.glivt.security.AppUserPrincipal;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.DriverRepository;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet-aware chat.
 *
 * <p>The full path is: React Native -> Spring Boot -> Python AI service -> Ollama.
 * Spring Boot never calls Ollama itself, so there is one model configuration,
 * one prompt system and one availability check.
 *
 * <p>Before the model is involved, this service detects the question's intent and
 * assembles a tenant-filtered slice of fleet data plus a deterministic answer
 * computed from that same data. If the model is unavailable the deterministic
 * answer is returned verbatim, so the user still gets their real numbers instead
 * of "AI model unavailable".
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final String OPERATION = "chat";
    /**
     * How many times the model may ask for more data before it must answer.
     * Bounded so a confused model cannot loop indefinitely against the database.
     */
    private static final int MAX_TOOL_ROUNDS = 1;
    /** Tool calls executed per round; also bounds prompt growth. */
    private static final int MAX_CALLS_PER_ROUND = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PythonAiClient pythonAiClient;
    private final AiProperties properties;
    private final FleetContextBuilder contextBuilder;
    private final AiEventRepository aiEventRepository;
    private final EventRepository eventRepository;
    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final AiGovernanceService governanceService;
    private final AiToolRegistry toolRegistry;

    public AiChatService(PythonAiClient pythonAiClient,
            AiProperties properties,
            FleetContextBuilder contextBuilder,
            AiEventRepository aiEventRepository,
            EventRepository eventRepository,
            VehicleRepository vehicleRepository,
            DeviceRepository deviceRepository,
            DriverRepository driverRepository,
            AiGovernanceService governanceService,
            AiToolRegistry toolRegistry) {
        this.pythonAiClient = pythonAiClient;
        this.properties = properties;
        this.contextBuilder = contextBuilder;
        this.aiEventRepository = aiEventRepository;
        this.eventRepository = eventRepository;
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.governanceService = governanceService;
        this.toolRegistry = toolRegistry;
    }

    @Transactional(readOnly = true)
    public ChatResponseDto chat(AppUserPrincipal user, ChatRequestDto request) {
        Long tenantId = user.getTenantId();
        String question = request.message() == null ? "" : request.message().trim();
        if (question.isEmpty()) {
            throw new BadRequestException("Message is required");
        }
        int maxChars = properties.getLimits().getMaxChatMessageChars();
        if (question.length() > maxChars) {
            throw new BadRequestException("Message exceeds the " + maxChars + " character limit");
        }

        Long selectedVehicleId = detectVehicleFromQuestion(tenantId, question);
        ChatIntent intent = ChatIntent.detect(question);
        if (selectedVehicleId == null) {
            selectedVehicleId = resolveSelectedVehicle(tenantId, request);
        } else {
            // Vehicle resolved explicitly from question.
            // If the intent is unknown, set it appropriately.
            if (intent == ChatIntent.UNKNOWN) {
                String lower = question.toLowerCase(Locale.ROOT);
                if (lower.contains("where") || lower.contains("location") || lower.contains("locate") || lower.contains("track")) {
                    intent = ChatIntent.CURRENT_LOCATION;
                } else {
                    intent = ChatIntent.VEHICLE_STATUS;
                }
            }
        }

        FleetContextBuilder.FleetContext fleet =
                contextBuilder.build(user, intent, selectedVehicleId, question);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("message", question);
        payload.put("intent", intent.name());
        payload.put("fleet_context", fleet.context());
        payload.put("deterministic_answer", fleet.deterministicAnswer());
        payload.put("history", trimHistory(request.history()));
        payload.put("citations", fleet.citations().stream()
                .map(c -> Map.of("type", c.type(), "id", c.id(), "label", c.label()))
                .toList());

        if (request.eventContext() != null) {
            payload.put("event_context", resolveEventContext(tenantId, request.eventContext()));
        }

        // Advertise tools only if the user's intent is UNKNOWN. If the intent is known,
        // the required fleet data is already in context. Skipping tools forces a fast,
        // single-round LLM response.
        List<Map<String, Object>> toolSchemas = intent == ChatIntent.UNKNOWN
                ? toolRegistry.schemasFor(user)
                : List.of();
        payload.put("tools", toolSchemas);

        AiResult<Map<String, Object>> result = runToolLoop(user, payload, tenantId,
                selectedVehicleId, toolSchemas);

        if (!result.success()) {
            // The AI service itself is unreachable. Answer from the deterministic
            // context that was already computed from the user's real fleet data.
            governanceService.recordWithPrompt(tenantId, OPERATION, "DETERMINISTIC",
                    properties.getOllama().getModel(), null, result.durationMs(),
                    result.errorCode().name());
            String errCode = (intent == ChatIntent.UNKNOWN) ? result.errorCode().name() : "NONE";
            return new ChatResponseDto(
                    fleet.deterministicAnswer(),
                    "DETERMINISTIC",
                    "DEGRADED",
                    properties.getOllama().getModel(),
                    result.durationMs(),
                    errCode,
                    toCitationDtos(fleet.citations()),
                    List.of());
        }

        Map<String, Object> body = result.payload();
        String reply = asString(body.get("reply"));
        String source = asString(body.getOrDefault("source", "DETERMINISTIC"));
        String mode = asString(body.getOrDefault("mode", "DEGRADED"));
        String fallbackReason = asString(body.get("fallbackReason"));

        if (reply == null || reply.isBlank()) {
            reply = fleet.deterministicAnswer();
            source = "DETERMINISTIC";
            mode = "DEGRADED";
            fallbackReason = "EMPTY_RESPONSE";
        }

        if (intent != ChatIntent.UNKNOWN && "DETERMINISTIC".equals(source)) {
            fallbackReason = "NONE";
        }

        governanceService.recordWithPrompt(tenantId, OPERATION, source,
                asString(body.get("model")), asString(body.get("promptVersion")),
                result.durationMs(), fallbackReason);

        log.debug("ai.chat tenantId={} userId={} intent={} source={} mode={} durationMs={}",
                tenantId, user.getUserId(), intent, source, mode, result.durationMs());

        return new ChatResponseDto(
                reply,
                source,
                mode,
                asString(body.getOrDefault("model", properties.getOllama().getModel())),
                result.durationMs(),
                fallbackReason,
                toCitationDtos(fleet.citations()),
                // The assistant only ever recommends. Anything that changes fleet
                // state is a separate, authorised, explicitly confirmed action.
                List.of());
    }

    /**
     * Bounded tool-calling loop.
     *
     * <p>The AI service owns the model; this service owns the data. So the loop
     * lives here: the model asks for data, Spring Boot executes those reads
     * tenant-scoped and permission-checked, and the results go back for another
     * turn until the model answers or the round budget runs out.
     *
     * <p>The model never receives a database handle, a query, or a tenant id it
     * could tamper with - only the results of accessors it was explicitly
     * allowed to call.
     */
    private AiResult<Map<String, Object>> runToolLoop(AppUserPrincipal user,
            Map<String, Object> payload, Long tenantId, Long selectedVehicleId,
            List<Map<String, Object>> toolSchemas) {

        List<Map<String, Object>> priorCalls = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();
        AiResult<Map<String, Object>> result = null;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            payload.put("prior_tool_calls", priorCalls);
            payload.put("tool_results", toolResults);

            result = pythonAiClient.postForMap("/v1/chat", payload,
                    new PythonAiClient.AiCallOptions(OPERATION, tenantId, selectedVehicleId,
                            properties.getPythonService().getChatTimeoutMs()));

            if (!result.success()) {
                return result;
            }
            Object rawCalls = result.payload().get("tool_calls");
            if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) {
                return result; // the model answered
            }

            int executed = 0;
            for (Object rawCall : calls) {
                if (!(rawCall instanceof Map<?, ?> callMap) || executed >= MAX_CALLS_PER_ROUND) {
                    continue;
                }
                String name = String.valueOf(callMap.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = callMap.get("arguments") instanceof Map<?, ?> argMap
                        ? (Map<String, Object>) argMap
                        : Map.of();

                Object toolResult = toolRegistry.execute(user, name, arguments);
                log.debug("ai.chat.tool tenantId={} userId={} tool={} round={}",
                        tenantId, user.getUserId(), name, round);

                priorCalls.add(Map.of("name", name, "arguments", arguments));
                toolResults.add(Map.of("name", name, "content", toJson(toolResult)));
                executed++;
            }

            if (executed == 0) {
                return result; // nothing runnable; let the model's text stand
            }
        }

        // Budget exhausted. Ask for a final answer from what was already gathered
        // rather than returning an empty tool-call turn to the user.
        payload.put("tools", List.of());
        payload.put("prior_tool_calls", priorCalls);
        payload.put("tool_results", toolResults);
        AiResult<Map<String, Object>> finalResult = pythonAiClient.postForMap("/v1/chat", payload,
                new PythonAiClient.AiCallOptions(OPERATION, tenantId, selectedVehicleId,
                        properties.getPythonService().getChatTimeoutMs()));
        return finalResult.success() ? finalResult : result;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"result could not be serialised\"}";
        }
    }

    /** Vehicle selection comes from the client but is always re-checked here. */
    private Long resolveSelectedVehicle(Long tenantId, ChatRequestDto request) {
        Long requested = request.selectedVehicleId();
        if (requested == null) {
            return null;
        }
        return vehicleRepository.findByIdAndTenantId(requested, tenantId)
                .map(Vehicle::getId)
                .orElse(null);
    }

    private List<Map<String, String>> trimHistory(List<ChatMessageDto> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int limit = properties.getLimits().getMaxChatHistoryMessages();
        List<ChatMessageDto> recent = history.size() <= limit
                ? history
                : history.subList(history.size() - limit, history.size());
        List<Map<String, String>> trimmed = new ArrayList<>();
        for (ChatMessageDto message : recent) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            trimmed.add(Map.of(
                    "role", message.role() == null ? "user" : message.role(),
                    "content", message.content()));
        }
        return trimmed;
    }

    /**
     * Re-resolves the event inside the authenticated tenant. The client-supplied
     * display fields are never trusted for data access - only the id is used, and
     * only against a tenant-scoped query.
     */
    private Map<String, Object> resolveEventContext(Long tenantId, EventChatContextDto requested) {
        String source = requested.source() == null ? "" : requested.source().trim().toUpperCase();
        Map<String, Object> context = new LinkedHashMap<>();

        if ("AI".equals(source)) {
            AiEvent event = aiEventRepository.findByIdAndTenantId(requested.eventId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("AI event not found"));
            context.put("eventId", event.getId());
            context.put("source", "AI");
            context.put("type", event.getEventType());
            context.put("severity", event.getSeverity());
            context.put("status", event.getStatus());
            context.put("occurrences", event.getOccurrenceCount());
            context.put("vehicle", vehicleLabel(tenantId, event.getVehicleId()));
            context.put("deviceId", event.getDeviceId());
            context.put("recordedAt", event.getCreatedAt() == null ? null : event.getCreatedAt().toString());
            context.put("location", coordinate(event.getLatitude(), event.getLongitude()));
            context.put("explanation", event.getExplanation());
            context.put("speedLimitKph", event.getSpeedLimitKph());
            context.put("speedLimitSource", event.getSpeedLimitSource());
            context.put("distanceFromRouteMeters", event.getDistanceFromRouteMeters());
            return context;
        }

        if ("STANDARD".equals(source)) {
            Event event = eventRepository.findByIdAndTenantId(requested.eventId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
            context.put("eventId", event.getId());
            context.put("source", "STANDARD");
            context.put("type", event.getEventType());
            context.put("severity", event.getSeverity());
            context.put("vehicle", vehicleLabel(tenantId, event.getVehicleId()));
            context.put("deviceId", event.getDeviceId());
            context.put("recordedAt", event.getServerTime() == null ? null : event.getServerTime().toString());
            context.put("location", event.getAddress() != null && !event.getAddress().isBlank()
                    ? event.getAddress()
                    : coordinate(event.getLatitude(), event.getLongitude()));
            context.put("detail", event.getDetail());
            return context;
        }

        throw new BadRequestException("Unsupported event source");
    }

    private String vehicleLabel(Long tenantId, Long vehicleId) {
        if (vehicleId == null) {
            return "Unassigned";
        }
        return vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .map(Vehicle::getName)
                .filter(name -> !name.isBlank())
                .orElse("Vehicle #" + vehicleId);
    }

    private static List<ChatResponseDto.CitationDto> toCitationDtos(
            List<FleetContextBuilder.Citation> citations) {
        List<ChatResponseDto.CitationDto> dtos = new ArrayList<>();
        for (FleetContextBuilder.Citation citation : citations) {
            dtos.add(new ChatResponseDto.CitationDto(citation.type(), citation.id(), citation.label()));
        }
        return dtos;
    }

    private static String coordinate(Double latitude, Double longitude) {
        if (latitude == null || longitude == null
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return "Location unavailable";
        }
        return String.format(java.util.Locale.ROOT, "%.5f, %.5f", latitude, longitude);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long detectVehicleFromQuestion(Long tenantId, String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalizedQuestion = question.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        List<Vehicle> vehicles = vehicleRepository.findByTenantId(tenantId);
        
        // 1. Match registration number
        for (Vehicle v : vehicles) {
            String reg = v.getRegistrationNumber();
            if (reg != null && !reg.isBlank()) {
                String normReg = reg.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                if (!normReg.isEmpty() && normalizedQuestion.contains(normReg)) {
                    return v.getId();
                }
            }
        }
        
        // 2. Match vehicle name (longest first to avoid subset matches)
        List<Vehicle> sortedByNameLen = new ArrayList<>(vehicles);
        sortedByNameLen.sort((v1, v2) -> Integer.compare(v2.getName().length(), v1.getName().length()));
        String upperQuestion = question.toUpperCase(Locale.ROOT);
        for (Vehicle v : sortedByNameLen) {
            String name = v.getName();
            if (name != null && !name.isBlank()) {
                String upperName = name.toUpperCase(Locale.ROOT);
                if (upperQuestion.contains(upperName)) {
                    return v.getId();
                }
            }
        }
        
        // 3. Match IMEI (via device repository)
        List<com.glivt.device.Device> devices = deviceRepository.findByTenantId(tenantId);
        for (com.glivt.device.Device d : devices) {
            String imei = d.getImei();
            if (imei != null && !imei.isBlank()) {
                String normImei = imei.replaceAll("[^0-9]", "");
                if (!normImei.isEmpty() && normalizedQuestion.contains(normImei)) {
                    if (d.getVehicleId() != null) {
                        return d.getVehicleId();
                    }
                }
            }
        }
        
        // 4. Match driver name (via driver repository)
        List<com.glivt.driver.Driver> drivers = driverRepository.findByTenantId(tenantId);
        for (com.glivt.driver.Driver dr : drivers) {
            String name = dr.getName();
            if (name != null && !name.isBlank()) {
                String upperName = name.toUpperCase(Locale.ROOT);
                if (upperQuestion.contains(upperName)) {
                    for (Vehicle v : vehicles) {
                        if (dr.getId().equals(v.getDriverId())) {
                            return v.getId();
                        }
                    }
                }
            }
        }
        
        return null;
    }
}
