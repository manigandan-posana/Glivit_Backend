package com.glivt.ai.client;

import java.util.Optional;

/**
 * Typed outcome of a call to the Python AI service.
 *
 * <p>Replaces the previous "catch (Exception) return null" pattern: a caller can
 * now tell a token mismatch from a timeout from a validation error and react
 * appropriately, and the UI can show a specific degraded state instead of a
 * generic failure.
 *
 * @param success   whether a usable payload was returned
 * @param mode      FULL_AI when the AI service answered, DEGRADED when the caller
 *                  must fall back to deterministic rules
 * @param source    where the answer came from (PYTHON_AI, RULE, ...)
 * @param errorCode the classified failure, {@link AiErrorCode#NONE} on success
 * @param message   short, safe-to-log description; never contains secrets or payloads
 * @param payload   the deserialised response body, null on failure
 * @param durationMs wall-clock duration of the call
 */
public record AiResult<T>(
        boolean success,
        AiMode mode,
        AiSource source,
        AiErrorCode errorCode,
        String message,
        T payload,
        long durationMs) {

    public enum AiMode {
        FULL_AI,
        DEGRADED
    }

    public enum AiSource {
        PYTHON_AI,
        RULE,
        NONE
    }

    public static <T> AiResult<T> ok(T payload, long durationMs) {
        return new AiResult<>(true, AiMode.FULL_AI, AiSource.PYTHON_AI, AiErrorCode.NONE, "OK",
                payload, durationMs);
    }

    public static <T> AiResult<T> failure(AiErrorCode errorCode, String message, long durationMs) {
        return new AiResult<>(false, AiMode.DEGRADED, AiSource.RULE, errorCode, message, null,
                durationMs);
    }

    public Optional<T> payloadOptional() {
        return Optional.ofNullable(payload);
    }

    /** True when the failure is a configuration problem an operator must fix. */
    public boolean isConfigurationFailure() {
        return errorCode == AiErrorCode.UNAUTHORIZED
                || errorCode == AiErrorCode.NOT_CONFIGURED
                || errorCode == AiErrorCode.VALIDATION_ERROR;
    }
}
