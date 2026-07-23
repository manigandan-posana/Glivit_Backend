package com.glivt.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeofenceSuggestionDto {
    private Long id;
    private String suggestedName;
    private double centerLatitude;
    private double centerLongitude;
    private double suggestedRadiusMeters;
    private int clusterPointCount;
    private double confidence;
    private String reasoning;
    private String polygonJson;
    private String status;
}
