package com.glivt.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of truth for AI configuration in the backend.
 *
 * <p>Spring Boot talks only to the Python AI service; it never calls Ollama
 * directly. The Ollama URL and model names live here purely so diagnostics can
 * report what the platform is <em>configured</em> to use and compare it with what
 * the AI service reports.
 *
 * <p>There is deliberately no default internal token. A blank token means AI
 * calls are rejected by the Python service, and in production
 * {@link AiConfigurationValidator} aborts startup rather than letting the
 * platform run permanently degraded.
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private final PythonService pythonService = new PythonService();
    private final Ollama ollama = new Ollama();
    private final Limits limits = new Limits();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** Local development escape hatch; refused when the profile is production. */
    private boolean allowDevToken = false;

    public PythonService getPythonService() {
        return pythonService;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public Limits getLimits() {
        return limits;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public boolean isAllowDevToken() {
        return allowDevToken;
    }

    public void setAllowDevToken(boolean allowDevToken) {
        this.allowDevToken = allowDevToken;
    }

    public static class PythonService {
        private String url = "http://127.0.0.1:8001";
        private String token = "";
        /** Connect timeout for every call. */
        private int connectTimeoutMs = 2000;
        /** Total timeout for ordinary (non-chat) calls. */
        private int timeoutMs = 10000;
        /** Total timeout for chat, which waits on token generation. */
        private int chatTimeoutMs = 5000;
        /** Total timeout for embedding batches. */
        private int embeddingTimeoutMs = 30000;
        /** Total timeout for readiness/diagnostics probes. */
        private int healthTimeoutMs = 3000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getChatTimeoutMs() {
            return chatTimeoutMs;
        }

        public void setChatTimeoutMs(int chatTimeoutMs) {
            this.chatTimeoutMs = chatTimeoutMs;
        }

        public int getEmbeddingTimeoutMs() {
            return embeddingTimeoutMs;
        }

        public void setEmbeddingTimeoutMs(int embeddingTimeoutMs) {
            this.embeddingTimeoutMs = embeddingTimeoutMs;
        }

        public int getHealthTimeoutMs() {
            return healthTimeoutMs;
        }

        public void setHealthTimeoutMs(int healthTimeoutMs) {
            this.healthTimeoutMs = healthTimeoutMs;
        }
    }

    /** Reported by diagnostics only - the backend never calls Ollama itself. */
    public static class Ollama {
        private String baseUrl = "http://127.0.0.1:11434";
        private String model = "qwen3.5:0.8b";
        private String embeddingModel = "qwen3-embedding:0.6b";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class Limits {
        private int maxChatMessageChars = 2000;
        private int maxChatHistoryMessages = 12;
        private int maxImageBytes = 5 * 1024 * 1024;
        private int chatRequestsPerMinute = 20;
        private int expensiveRequestsPerMinute = 30;

        public int getMaxChatMessageChars() {
            return maxChatMessageChars;
        }

        public void setMaxChatMessageChars(int maxChatMessageChars) {
            this.maxChatMessageChars = maxChatMessageChars;
        }

        public int getMaxChatHistoryMessages() {
            return maxChatHistoryMessages;
        }

        public void setMaxChatHistoryMessages(int maxChatHistoryMessages) {
            this.maxChatHistoryMessages = maxChatHistoryMessages;
        }

        public int getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(int maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public int getChatRequestsPerMinute() {
            return chatRequestsPerMinute;
        }

        public void setChatRequestsPerMinute(int chatRequestsPerMinute) {
            this.chatRequestsPerMinute = chatRequestsPerMinute;
        }

        public int getExpensiveRequestsPerMinute() {
            return expensiveRequestsPerMinute;
        }

        public void setExpensiveRequestsPerMinute(int expensiveRequestsPerMinute) {
            this.expensiveRequestsPerMinute = expensiveRequestsPerMinute;
        }
    }

    public static class CircuitBreaker {
        private boolean enabled = true;
        /** Consecutive transport failures before the breaker opens. */
        private int failureThreshold = 5;
        /** How long the breaker stays open before a trial request is allowed. */
        private int openMillis = 30000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getOpenMillis() {
            return openMillis;
        }

        public void setOpenMillis(int openMillis) {
            this.openMillis = openMillis;
        }
    }
}
