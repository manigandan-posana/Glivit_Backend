package com.glivt.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OllamaAiClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiClient.class);

    private final ChatModel chatModel;

    @Value("${spring.ai.ollama.chat.options.model:qwen3.5:2b}")
    private String modelName;

    public OllamaAiClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Generate structured, concise text summary or explanation using Qwen 3.5.
     * Falls back to deterministic text if Ollama is offline or fails.
     */
    public String generateExplanation(String systemPrompt, String userContext, String fallbackText) {
        if (chatModel == null) {
            log.debug("Spring AI ChatModel is not active. Using rule-based fallback.");
            return fallbackText;
        }

        try {
            String fullPromptText = (systemPrompt != null ? systemPrompt + "\n\n" : "") + userContext;
            Prompt prompt = new Prompt(fullPromptText);
            String response = chatModel.call(prompt).getResult().getOutput().getText();

            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception ex) {
            log.warn("Ollama AI invocation failed: {}. Utilizing deterministic fallback.", ex.getMessage());
        }

        return fallbackText;
    }
}
