package com.glivt.common;

/**
 * Per-request metadata (correlation id, client ip, user agent) held in a
 * ThreadLocal so services and the audit trail can access it without plumbing
 * HttpServletRequest through every layer. Cleared by {@link CorrelationIdFilter}.
 */
public final class RequestContext {

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_AGENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String correlationId, String clientIp, String userAgent) {
        CORRELATION_ID.set(correlationId);
        CLIENT_IP.set(clientIp);
        USER_AGENT.set(userAgent);
    }

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static String getClientIp() {
        return CLIENT_IP.get();
    }

    public static String getUserAgent() {
        return USER_AGENT.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
        CLIENT_IP.remove();
        USER_AGENT.remove();
    }
}
