package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.glivt.ai.client.AiErrorCode;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Transport-level behaviour of the AI client: authentication, each distinct
 * failure mode, and the circuit breaker. A real loopback HTTP server is used so
 * the JDK client's actual timeout and connection handling is exercised rather
 * than mocked away.
 */
class PythonAiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private com.glivt.ai.config.AiProperties properties(String baseUrl, String token) {
        com.glivt.ai.config.AiProperties properties = new com.glivt.ai.config.AiProperties();
        properties.getPythonService().setUrl(baseUrl);
        properties.getPythonService().setToken(token);
        properties.getPythonService().setConnectTimeoutMs(500);
        properties.getPythonService().setTimeoutMs(1500);
        return properties;
    }

    private String startServer(int status, String body, AtomicReference<String> tokenSeen)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (tokenSeen != null) {
                tokenSeen.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            }
            respond(exchange, status, body);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void sendsInternalTokenAndParsesSuccess() throws Exception {
        AtomicReference<String> tokenSeen = new AtomicReference<>();
        String url = startServer(200, "{\"anomaly_score\":0.42}", tokenSeen);
        PythonAiClient client = new PythonAiClient(properties(url, "shared-secret"));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score",
                Map.of("tenant_id", 1), PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.success()).isTrue();
        assertThat(result.mode()).isEqualTo(AiResult.AiMode.FULL_AI);
        assertThat(result.source()).isEqualTo(AiResult.AiSource.PYTHON_AI);
        assertThat(result.errorCode()).isEqualTo(AiErrorCode.NONE);
        assertThat(result.payload()).containsEntry("anomaly_score", 0.42);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        // The shared secret is transmitted in the agreed header.
        assertThat(tokenSeen.get()).isEqualTo("shared-secret");
    }

    @Test
    void classifiesTokenMismatchAsUnauthorized() throws Exception {
        String url = startServer(401, "{\"detail\":\"Unauthorized\"}", null);
        PythonAiClient client = new PythonAiClient(properties(url, "wrong-secret"));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(AiErrorCode.UNAUTHORIZED);
        assertThat(result.isConfigurationFailure()).isTrue();
        assertThat(result.mode()).isEqualTo(AiResult.AiMode.DEGRADED);
        // The message must point at the fix without echoing the secret.
        assertThat(result.message()).contains("AI_INTERNAL_TOKEN");
        assertThat(result.message()).doesNotContain("wrong-secret");
    }

    @Test
    void doesNotCallServiceWhenNoTokenIsConfigured() throws Exception {
        String url = startServer(200, "{}", null);
        PythonAiClient client = new PythonAiClient(properties(url, ""));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.errorCode()).isEqualTo(AiErrorCode.NOT_CONFIGURED);
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    void classifiesValidationErrorSeparatelyFromServiceError() throws Exception {
        String url = startServer(422, "{\"detail\":\"field required\"}", null);
        PythonAiClient client = new PythonAiClient(properties(url, "secret"));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.errorCode()).isEqualTo(AiErrorCode.VALIDATION_ERROR);
        // A contract mismatch is our bug, not an outage, so it is not transient.
        assertThat(result.errorCode().isTransient()).isFalse();
    }

    @Test
    void classifiesRateLimitAndServerError() throws Exception {
        String rateLimited = startServer(429, "{}", null);
        PythonAiClient client = new PythonAiClient(properties(rateLimited, "secret"));
        assertThat(client.postForMap("/v1/chat", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L)).errorCode())
                .isEqualTo(AiErrorCode.RATE_LIMITED);
        server.stop(0);

        String serverError = startServer(500, "{}", null);
        PythonAiClient errorClient = new PythonAiClient(properties(serverError, "secret"));
        assertThat(errorClient.postForMap("/v1/chat", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L)).errorCode())
                .isEqualTo(AiErrorCode.SERVICE_ERROR);
    }

    @Test
    void classifiesMalformedResponse() throws Exception {
        String url = startServer(200, "not json at all", null);
        PythonAiClient client = new PythonAiClient(properties(url, "secret"));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.errorCode()).isEqualTo(AiErrorCode.MALFORMED_RESPONSE);
    }

    @Test
    void classifiesConnectionRefused() {
        // Port 1 refuses connections on every supported platform.
        PythonAiClient client = new PythonAiClient(properties("http://127.0.0.1:1", "secret"));

        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode())
                .isIn(AiErrorCode.CONNECTION_REFUSED, AiErrorCode.CONNECT_TIMEOUT);
        assertThat(result.errorCode().isTransient()).isTrue();
    }

    @Test
    void classifiesReadTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(3000); // longer than the 1500 ms total timeout
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        PythonAiClient client = new PythonAiClient(properties(url, "secret"));
        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.errorCode()).isEqualTo(AiErrorCode.READ_TIMEOUT);
    }

    @Test
    void circuitBreakerOpensAfterRepeatedTransportFailuresAndFailsFast() {
        com.glivt.ai.config.AiProperties properties = properties("http://127.0.0.1:1", "secret");
        properties.getCircuitBreaker().setFailureThreshold(3);
        properties.getCircuitBreaker().setOpenMillis(30_000);
        PythonAiClient client = new PythonAiClient(properties);

        for (int i = 0; i < 3; i++) {
            client.postForMap("/v1/anomaly/score", Map.of(),
                    PythonAiClient.AiCallOptions.of("test", 1L));
        }
        assertThat(client.circuitBreaker().isOpen()).isTrue();

        long start = System.nanoTime();
        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.errorCode()).isEqualTo(AiErrorCode.CIRCUIT_OPEN);
        // Fail fast: no connect attempt is made while the breaker is open.
        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    void validationErrorDoesNotTripTheCircuitBreaker() throws Exception {
        String url = startServer(422, "{}", null);
        com.glivt.ai.config.AiProperties properties = properties(url, "secret");
        properties.getCircuitBreaker().setFailureThreshold(2);
        PythonAiClient client = new PythonAiClient(properties);

        for (int i = 0; i < 5; i++) {
            client.postForMap("/v1/anomaly/score", Map.of(),
                    PythonAiClient.AiCallOptions.of("test", 1L));
        }
        // A bad request from us is not evidence the service is unhealthy.
        assertThat(client.circuitBreaker().isOpen()).isFalse();
    }

    @Test
    void successClosesAnOpenCircuit() throws Exception {
        String url = startServer(200, "{\"ok\":true}", null);
        com.glivt.ai.config.AiProperties properties = properties(url, "secret");
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setOpenMillis(1000);
        PythonAiClient client = new PythonAiClient(properties);

        client.circuitBreaker().recordFailure(AiErrorCode.CONNECTION_REFUSED);
        assertThat(client.circuitBreaker().isOpen()).isTrue();

        Thread.sleep(1100); // let the breaker go half-open
        AiResult<Map<String, Object>> result = client.postForMap("/v1/anomaly/score", Map.of(),
                PythonAiClient.AiCallOptions.of("test", 1L));

        assertThat(result.success()).isTrue();
        assertThat(client.circuitBreaker().isOpen()).isFalse();
    }
}
