package com.glivt.ai.service;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class PythonAiClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiClient.class);

    private final RestTemplate restTemplate;
    private final String pythonServiceUrl;
    private final String internalToken;

    public PythonAiClient(
            @Value("${app.ai.python-service.url:http://localhost:8001}") String pythonServiceUrl,
            @Value("${app.ai.python-service.token:}") String internalToken,
            @Value("${app.ai.python-service.timeout-ms:5000}") int timeoutMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
        this.pythonServiceUrl = pythonServiceUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
    }

    public <T> T post(String endpoint, Object requestPayload, Class<T> responseType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            HttpEntity<Object> entity = new HttpEntity<>(requestPayload, headers);
            String url = pythonServiceUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
            return restTemplate.postForObject(url, entity, responseType);
        } catch (Exception ex) {
            log.warn("Python AI service call to {} failed: {}. Executing rule-based fallback.", endpoint, ex.getMessage());
            return null;
        }
    }
}
