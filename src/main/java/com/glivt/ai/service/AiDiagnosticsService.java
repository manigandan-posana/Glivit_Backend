package com.glivt.ai.service;

import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.config.AiConfigurationValidator;
import com.glivt.ai.config.AiProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * SUPER_ADMIN diagnostics for the AI stack.
 *
 * <p>Availability is read from the AI service's own {@code /ready} probe rather
 * than by the backend contacting Ollama directly - one availability check, one
 * source of truth. The result is cached briefly so opening the screen (or
 * refreshing it) cannot hammer the probe.
 *
 * <p>The internal token is never returned. Only a short fingerprint is exposed,
 * which is enough to confirm that both services share the same secret without
 * revealing it.
 */
@Service
public class AiDiagnosticsService {

    private static final long CACHE_MILLIS = 5_000L;

    private final PythonAiClient pythonAiClient;
    private final AiProperties properties;
    private final AiOutboxWorker outboxWorker;
    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    public AiDiagnosticsService(PythonAiClient pythonAiClient, AiProperties properties,
            AiOutboxWorker outboxWorker) {
        this.pythonAiClient = pythonAiClient;
        this.properties = properties;
        this.outboxWorker = outboxWorker;
    }

    private record CachedSnapshot(Map<String, Object> body, long createdAt) {
    }

    public Map<String, Object> diagnostics(boolean forceRefresh) {
        CachedSnapshot cached = cache.get();
        if (!forceRefresh && cached != null
                && System.currentTimeMillis() - cached.createdAt() < CACHE_MILLIS) {
            return cached.body();
        }
        Map<String, Object> snapshot = probe();
        cache.set(new CachedSnapshot(snapshot, System.currentTimeMillis()));
        return snapshot;
    }

    /** Cached availability used by non-admin surfaces that only need the mode. */
    public String currentMode() {
        Object mode = diagnostics(false).get("mode");
        return mode == null ? "UNKNOWN" : String.valueOf(mode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probe() {
        Map<String, Object> body = new LinkedHashMap<>();
        Instant checkedAt = Instant.now();

        // /ready is unauthenticated and fast; the AI service owns the Ollama
        // availability check so there is exactly one source of truth.
        AiResult<Map<String, Object>> ready = pythonAiClient.get(
                "/ready",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                },
                new PythonAiClient.AiCallOptions("diagnostics", null, null,
                        properties.getPythonService().getHealthTimeoutMs()));

        if (!ready.success() || ready.payload() == null) {
            body.put("pythonService", "DOWN");
            body.put("ollama", "UNKNOWN");
            body.put("chatModel", "UNKNOWN");
            body.put("embeddingModel", "UNKNOWN");
            body.put("mode", "PYTHON_SERVICE_UNAVAILABLE");
            body.put("reason", "The Python AI service could not be reached at "
                    + properties.getPythonService().getUrl());
        } else {
            Map<String, Object> payload = ready.payload();
            String serviceStatus = String.valueOf(payload.getOrDefault("status", "UNKNOWN"));
            String serviceMode = String.valueOf(payload.getOrDefault("mode", "UNKNOWN"));
            String ollama = String.valueOf(payload.getOrDefault("ollama", "UNKNOWN"));
            String chatModel = String.valueOf(payload.getOrDefault("chatModel", "UNKNOWN"));
            String embeddingModel = String.valueOf(payload.getOrDefault("embeddingModel", "UNKNOWN"));

            body.put("pythonService", "READY".equals(serviceStatus) ? "UP" : "DEGRADED");
            body.put("ollama", ollama);
            body.put("chatModel", chatModel);
            body.put("embeddingModel", embeddingModel);
            body.put("mode", "FULL_AI".equals(serviceMode) ? "FULL_AI"
                    : "DEGRADED".equals(serviceMode) ? "RULE_ENGINE_FALLBACK" : serviceMode);
            body.put("reason", payload.get("reason"));
            body.put("aiServiceChatModel", payload.get("chatModelName"));
            body.put("aiServiceEmbeddingModel", payload.get("embeddingModelName"));
            body.put("installedOllamaModels", payload.getOrDefault("installedOllamaModels", List.of()));
            body.put("localModelsLoaded", payload.getOrDefault("localModelsLoaded", List.of()));
        }

        // Configuration as the BACKEND sees it, so a mismatch is obvious.
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("pythonServiceUrl", properties.getPythonService().getUrl());
        configuration.put("ollamaBaseUrl", properties.getOllama().getBaseUrl());
        configuration.put("configuredChatModel", properties.getOllama().getModel());
        configuration.put("configuredEmbeddingModel", properties.getOllama().getEmbeddingModel());
        configuration.put("internalTokenConfigured", pythonAiClient.isConfigured());
        // Fingerprint only - the token itself is never exposed.
        configuration.put("internalTokenFingerprint",
                AiConfigurationValidator.fingerprint(properties.getPythonService().getToken()));
        configuration.put("timeouts", Map.of(
                "connectMs", properties.getPythonService().getConnectTimeoutMs(),
                "defaultMs", properties.getPythonService().getTimeoutMs(),
                "chatMs", properties.getPythonService().getChatTimeoutMs(),
                "embeddingMs", properties.getPythonService().getEmbeddingTimeoutMs(),
                "healthMs", properties.getPythonService().getHealthTimeoutMs()));
        body.put("configuration", configuration);

        body.put("circuitBreaker", Map.of(
                "open", pythonAiClient.circuitBreaker().isOpen(),
                "consecutiveFailures", pythonAiClient.circuitBreaker().consecutiveFailures()));

        AiOutboxWorker.OutboxMetrics metrics = outboxWorker.metrics();
        body.put("pipeline", Map.of(
                "outboxPending", metrics.pending(),
                "coalescedPositions", metrics.coalesced(),
                "droppedEvaluations", metrics.dropped(),
                "rejectedAsyncTasks", metrics.rejectedTasks()));

        body.put("lastCheckedAt", checkedAt.toString());
        return body;
    }
}
