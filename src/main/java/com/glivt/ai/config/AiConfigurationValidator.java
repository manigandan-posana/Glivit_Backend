package com.glivt.ai.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Validates AI configuration at startup and logs a safe summary.
 *
 * <p>Production refuses to start without a shared internal token: running
 * without one means every AI call is rejected by the Python service and the
 * platform silently serves degraded results forever. Development is allowed to
 * fall back to a documented local token, but only explicitly
 * ({@code app.ai.allow-dev-token=true}) and always with a loud warning.
 *
 * <p>The token itself is never logged - only a short SHA-256 fingerprint, which
 * is enough to confirm that Spring Boot and FastAPI share the same secret.
 */
@Component
public class AiConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(AiConfigurationValidator.class);

    /** Documented local development token. Must match the Python service. */
    public static final String LOCAL_DEV_TOKEN = "glivt-local-dev-token-do-not-use-in-production";

    private final AiProperties properties;
    private final Environment environment;

    public AiConfigurationValidator(AiProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validateAndLog() {
        boolean production = isProduction();
        String token = properties.getPythonService().getToken();

        if (token == null || token.isBlank()) {
            if (production) {
                throw new IllegalStateException(
                        "AI_INTERNAL_TOKEN is required in production. Generate one with "
                                + "`openssl rand -base64 32` and set the identical value in the "
                                + "Python AI service.");
            }
            if (properties.isAllowDevToken()) {
                properties.getPythonService().setToken(LOCAL_DEV_TOKEN);
                log.warn("AI_INTERNAL_TOKEN is unset and app.ai.allow-dev-token=true - using the "
                        + "DOCUMENTED LOCAL DEV TOKEN. This is refused in production.");
            } else {
                log.error("AI_INTERNAL_TOKEN is unset. The Python AI service will reject every "
                        + "call with 401 and all AI features will run in deterministic fallback "
                        + "mode. Set AI_INTERNAL_TOKEN (identical in both services).");
            }
        }

        log.info("AI configuration:");
        log.info("  pythonServiceUrl      = {}", properties.getPythonService().getUrl());
        log.info("  pythonTimeoutMs       = {} (chat {} / embedding {} / health {})",
                properties.getPythonService().getTimeoutMs(),
                properties.getPythonService().getChatTimeoutMs(),
                properties.getPythonService().getEmbeddingTimeoutMs(),
                properties.getPythonService().getHealthTimeoutMs());
        log.info("  ollamaBaseUrl         = {} (reported for diagnostics; the backend never calls "
                + "Ollama directly)", properties.getOllama().getBaseUrl());
        log.info("  ollamaModel           = {}", properties.getOllama().getModel());
        log.info("  ollamaEmbeddingModel  = {}", properties.getOllama().getEmbeddingModel());
        log.info("  internalTokenSet      = {}", !isBlank(properties.getPythonService().getToken()));
        log.info("  internalTokenPrint    = {}", fingerprint(properties.getPythonService().getToken()));
    }

    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        String appEnv = environment.getProperty("app.environment", "");
        return "prod".equalsIgnoreCase(appEnv) || "production".equalsIgnoreCase(appEnv);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Short, non-reversible marker so two services can be compared without ever
     * printing the shared secret.
     */
    public static String fingerprint(String token) {
        if (isBlank(token)) {
            return "unset";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException ex) {
            return "unavailable";
        }
    }
}
