package com.glivt.ai.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticSearchResponseDto {
    private String query;
    private List<SearchMatchDto> matches;
    /** EMBEDDING for real vector search, KEYWORD for the labelled degraded mode. */
    private String source;
    private boolean degraded;
    private String errorCode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchMatchDto {
        private String id;
        /** AI_EVENT | ALERT | TRIP | MAINTENANCE - the real source table. */
        private String sourceType;
        /** The real source record id, so the UI can open the underlying record. */
        private Long sourceId;
        private double score;
        private String content;
        private Map<String, Object> metadata;
    }
}
