package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.glivt.ai.service.OllamaAiClient;
import com.glivt.ai.service.PythonAiClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

@ExtendWith(MockitoExtension.class)
class AiFallbackTest {

    @Mock private ChatModel chatModel;

    @Test
    void pythonClientReturnsNullWhenServiceUnavailable() {
        // Port 1 refuses connections; the client must swallow it and fall back.
        PythonAiClient client = new PythonAiClient("http://127.0.0.1:1/", "token", 300);
        Object result = client.post("/v1/anomaly/score", Map.of("tenant_id", 1), Map.class);
        assertThat(result).isNull();
    }

    @Test
    void ollamaClientReturnsTemplateWhenModelThrows() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("ollama down"));
        OllamaAiClient client = new OllamaAiClient(chatModel);
        String result = client.generateExplanation("system", "context", "DETERMINISTIC_FALLBACK");
        assertThat(result).isEqualTo("DETERMINISTIC_FALLBACK");
    }
}
