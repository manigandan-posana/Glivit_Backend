package com.glivt.ai.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.config.AiProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The one HTTP client the backend uses to reach the Python AI service.
 *
 * <p>Design notes:
 * <ul>
 *   <li>a single pooled {@link HttpClient} (keep-alive, HTTP/1.1) shared by every
 *       AI call, so a chat request does not open a fresh connection;</li>
 *   <li>per-call total timeouts plus a client-wide connect timeout, chosen by
 *       {@link AiCallOptions} so a 90-second chat cannot use the same budget as
 *       a 10-second anomaly score;</li>
 *   <li>a circuit breaker so a dead AI service does not stall the AI thread pool
 *       for every GPS packet;</li>
 *   <li>every distinct failure is classified into an {@link AiErrorCode} rather
 *       than swallowed - the caller decides how to degrade.</li>
 * </ul>
 *
 * <p>Logging carries correlation id, tenant, operation, vehicle, duration, result
 * source and error code. It never logs the internal token, a JWT, chat history,
 * fleet context or image payloads.
 */
@Component
public class PythonAiClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiClient.class);
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AiProperties properties;
    private final HttpClient httpClient;
    private final AiCircuitBreaker circuitBreaker;

    public PythonAiClient(AiProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getPythonService().getConnectTimeoutMs()))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.circuitBreaker = new AiCircuitBreaker(
                "python-ai",
                properties.getCircuitBreaker().isEnabled(),
                properties.getCircuitBreaker().getFailureThreshold(),
                properties.getCircuitBreaker().getOpenMillis());
    }

    /** Per-call metadata used for timeouts and structured logging. */
    public record AiCallOptions(
            String operation,
            Long tenantId,
            Long vehicleId,
            int totalTimeoutMs) {

        public static AiCallOptions of(String operation, Long tenantId) {
            return new AiCallOptions(operation, tenantId, null, 0);
        }

        public AiCallOptions withVehicle(Long vehicleId) {
            return new AiCallOptions(operation, tenantId, vehicleId, totalTimeoutMs);
        }

        public AiCallOptions withTimeout(int timeoutMs) {
            return new AiCallOptions(operation, tenantId, vehicleId, timeoutMs);
        }
    }

    public AiCircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public boolean isConfigured() {
        String token = properties.getPythonService().getToken();
        return token != null && !token.isBlank();
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    public <T> AiResult<T> post(String endpoint, Object body, Class<T> responseType,
            AiCallOptions options) {
        return execute(endpoint, body, options, json -> objectMapper.readValue(json, responseType));
    }

    public <T> AiResult<T> post(String endpoint, Object body, TypeReference<T> responseType,
            AiCallOptions options) {
        return execute(endpoint, body, options, json -> objectMapper.readValue(json, responseType));
    }

    public AiResult<Map<String, Object>> postForMap(String endpoint, Object body,
            AiCallOptions options) {
        return post(endpoint, body, new TypeReference<Map<String, Object>>() {
        }, options);
    }

    /** GET, used for the unauthenticated {@code /ready} and {@code /health} probes. */
    public <T> AiResult<T> get(String endpoint, Class<T> responseType, AiCallOptions options) {
        return execute(endpoint, null, options, json -> objectMapper.readValue(json, responseType));
    }

    public <T> AiResult<T> get(String endpoint, TypeReference<T> responseType, AiCallOptions options) {
        return execute(endpoint, null, options, json -> objectMapper.readValue(json, responseType));
    }

    @FunctionalInterface
    private interface Deserializer<T> {
        T read(String json) throws IOException;
    }

    private <T> AiResult<T> execute(String endpoint, Object body, AiCallOptions options,
            Deserializer<T> deserializer) {
        long start = System.nanoTime();
        boolean requiresAuth = endpoint.startsWith("/v1/");

        if (requiresAuth && !isConfigured()) {
            AiResult<T> result = AiResult.failure(AiErrorCode.NOT_CONFIGURED,
                    "No AI internal token configured; call not attempted", elapsedMs(start));
            logResult(endpoint, options, result);
            return result;
        }

        if (!circuitBreaker.allowRequest()) {
            AiResult<T> result = AiResult.failure(AiErrorCode.CIRCUIT_OPEN,
                    "AI circuit breaker is open; call not attempted", elapsedMs(start));
            logResult(endpoint, options, result);
            return result;
        }

        int timeoutMs = options.totalTimeoutMs() > 0
                ? options.totalTimeoutMs()
                : properties.getPythonService().getTimeoutMs();

        HttpRequest request;
        try {
            request = buildRequest(endpoint, body, requiresAuth, timeoutMs, options);
        } catch (Exception ex) {
            AiResult<T> result = AiResult.failure(AiErrorCode.MALFORMED_RESPONSE,
                    "Could not serialise request body: " + ex.getClass().getSimpleName(),
                    elapsedMs(start));
            logResult(endpoint, options, result);
            return result;
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            AiResult<T> result = handleResponse(response, deserializer, elapsedMs(start));
            if (result.success()) {
                circuitBreaker.recordSuccess();
            } else {
                circuitBreaker.recordFailure(result.errorCode());
            }
            logResult(endpoint, options, result);
            return result;
        } catch (HttpConnectTimeoutException ex) {
            return fail(endpoint, options, AiErrorCode.CONNECT_TIMEOUT,
                    "Connect timed out after " + properties.getPythonService().getConnectTimeoutMs() + " ms",
                    start);
        } catch (HttpTimeoutException ex) {
            return fail(endpoint, options, AiErrorCode.READ_TIMEOUT,
                    "Response timed out after " + timeoutMs + " ms", start);
        } catch (ConnectException ex) {
            return fail(endpoint, options, AiErrorCode.CONNECTION_REFUSED,
                    "Connection refused by " + properties.getPythonService().getUrl(), start);
        } catch (IOException ex) {
            // The JDK client wraps a refused connection in a plain IOException on
            // some platforms, so inspect the cause chain before giving up.
            AiErrorCode code = looksLikeConnectionRefused(ex)
                    ? AiErrorCode.CONNECTION_REFUSED
                    : AiErrorCode.UNKNOWN;
            return fail(endpoint, options, code, ex.getClass().getSimpleName(), start);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fail(endpoint, options, AiErrorCode.UNKNOWN, "Interrupted", start);
        }
    }

    private HttpRequest buildRequest(String endpoint, Object body, boolean requiresAuth,
            int timeoutMs, AiCallOptions options) throws IOException {
        String base = properties.getPythonService().getUrl().replaceAll("/+$", "");
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");

        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-Id", correlationId);
        }
        if (options.tenantId() != null) {
            builder.header("X-Tenant-Id", String.valueOf(options.tenantId()));
        }
        if (requiresAuth) {
            builder.header(TOKEN_HEADER, properties.getPythonService().getToken());
        }

        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        return builder.build();
    }

    private <T> AiResult<T> handleResponse(HttpResponse<String> response,
            Deserializer<T> deserializer, long durationMs) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return AiResult.failure(AiErrorCode.UNAUTHORIZED,
                    "AI service rejected the internal token (HTTP " + status
                            + "). Spring Boot and the AI service must share the same AI_INTERNAL_TOKEN.",
                    durationMs);
        }
        if (status == 422) {
            return AiResult.failure(AiErrorCode.VALIDATION_ERROR,
                    "AI service rejected the request payload (HTTP 422): " + truncate(response.body()),
                    durationMs);
        }
        if (status == 429) {
            return AiResult.failure(AiErrorCode.RATE_LIMITED,
                    "AI service is rate limiting (HTTP 429)", durationMs);
        }
        if (status >= 500) {
            return AiResult.failure(AiErrorCode.SERVICE_ERROR,
                    "AI service error (HTTP " + status + ")", durationMs);
        }
        if (status < 200 || status >= 300) {
            return AiResult.failure(AiErrorCode.UNKNOWN,
                    "Unexpected AI service status " + status, durationMs);
        }

        String bodyText = response.body();
        if (bodyText == null || bodyText.isBlank()) {
            return AiResult.failure(AiErrorCode.MALFORMED_RESPONSE,
                    "AI service returned an empty body", durationMs);
        }
        try {
            T payload = deserializer.read(bodyText);
            if (payload == null) {
                return AiResult.failure(AiErrorCode.MALFORMED_RESPONSE,
                        "AI service returned a null payload", durationMs);
            }
            return AiResult.ok(payload, durationMs);
        } catch (Exception ex) {
            return AiResult.failure(AiErrorCode.MALFORMED_RESPONSE,
                    "Could not parse AI service response: " + ex.getClass().getSimpleName(),
                    durationMs);
        }
    }

    private <T> AiResult<T> fail(String endpoint, AiCallOptions options, AiErrorCode code,
            String message, long start) {
        AiResult<T> result = AiResult.failure(code, message, elapsedMs(start));
        circuitBreaker.recordFailure(code);
        logResult(endpoint, options, result);
        return result;
    }

    private void logResult(String endpoint, AiCallOptions options, AiResult<?> result) {
        if (result.success()) {
            log.debug("ai.call correlationId={} tenantId={} operation={} vehicleId={} endpoint={} "
                            + "durationMs={} source={} result=OK",
                    mdcCorrelationId(), options.tenantId(), options.operation(), options.vehicleId(),
                    endpoint, result.durationMs(), result.source());
            return;
        }
        // Configuration problems are actionable and must be visible; transient
        // outages are expected and logged at a lower level so they cannot flood.
        if (result.isConfigurationFailure()) {
            log.error("ai.call correlationId={} tenantId={} operation={} vehicleId={} endpoint={} "
                            + "durationMs={} source={} errorCode={} message={}",
                    mdcCorrelationId(), options.tenantId(), options.operation(), options.vehicleId(),
                    endpoint, result.durationMs(), result.source(), result.errorCode(),
                    result.message());
        } else {
            log.warn("ai.call correlationId={} tenantId={} operation={} vehicleId={} endpoint={} "
                            + "durationMs={} source={} errorCode={} message={}",
                    mdcCorrelationId(), options.tenantId(), options.operation(), options.vehicleId(),
                    endpoint, result.durationMs(), result.source(), result.errorCode(),
                    result.message());
        }
    }

    private static String mdcCorrelationId() {
        String value = MDC.get("correlationId");
        return value == null ? "-" : value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean looksLikeConnectionRefused(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("refused")) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
