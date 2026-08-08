package com.glivt.ai.client;

/**
 * Every distinct way a call to the Python AI service can fail. Each is handled
 * separately so an operator can tell a misconfigured token apart from a crashed
 * service, and so the UI can show the right degraded state.
 */
public enum AiErrorCode {

    /** No failure. */
    NONE,
    /** The AI service rejected our internal token (401): the secrets differ. */
    UNAUTHORIZED,
    /** Nothing is listening on the configured URL. */
    CONNECTION_REFUSED,
    /** TCP connect did not complete inside the connect timeout. */
    CONNECT_TIMEOUT,
    /** Connected, but the response did not arrive inside the read/total timeout. */
    READ_TIMEOUT,
    /** The AI service rejected the request body (422): a contract mismatch. */
    VALIDATION_ERROR,
    /** The AI service is rate limiting us (429). */
    RATE_LIMITED,
    /** The AI service raised an unhandled error (5xx). */
    SERVICE_ERROR,
    /** A 2xx response that could not be parsed into the expected shape. */
    MALFORMED_RESPONSE,
    /** The breaker is open: recent calls failed, so this one was not attempted. */
    CIRCUIT_OPEN,
    /** No internal token is configured, so the call was not attempted. */
    NOT_CONFIGURED,
    /** Any other client-side failure. */
    UNKNOWN;

    /** True when retrying later is likely to succeed. */
    public boolean isTransient() {
        return this == CONNECTION_REFUSED
                || this == CONNECT_TIMEOUT
                || this == READ_TIMEOUT
                || this == SERVICE_ERROR
                || this == RATE_LIMITED
                || this == CIRCUIT_OPEN;
    }
}
