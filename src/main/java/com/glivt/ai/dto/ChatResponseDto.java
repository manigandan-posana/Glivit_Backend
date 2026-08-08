package com.glivt.ai.dto;

import java.util.List;

/**
 * Chat answer returned to the app.
 *
 * @param source      OLLAMA when the language model answered, DETERMINISTIC when
 *                    the answer was computed from fleet data because the model
 *                    was unavailable
 * @param mode        FULL_AI or DEGRADED, so the UI can show an honest state
 * @param citations   fleet entities the answer refers to, for deep linking
 * @param suggestedActions recommendations only - the assistant never performs an
 *                    action; each still requires explicit user confirmation and
 *                    a separate authorised backend call
 */
public record ChatResponseDto(
        String reply,
        String source,
        String mode,
        String model,
        Long durationMs,
        String fallbackReason,
        List<CitationDto> citations,
        List<SuggestedActionDto> suggestedActions
) {

    public record CitationDto(String type, Long id, String label) {
    }

    public record SuggestedActionDto(String action, String label, String targetType, Long targetId,
            boolean requiresConfirmation) {
    }
}
