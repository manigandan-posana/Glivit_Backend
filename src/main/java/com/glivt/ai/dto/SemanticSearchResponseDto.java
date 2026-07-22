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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchMatchDto {
        private String id;
        private double score;
        private String content;
        private Map<String, Object> metadata;
    }
}
