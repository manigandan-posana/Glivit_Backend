package com.glivt.project.dto;

import com.glivt.project.Project;
import java.time.Instant;

public record ProjectDto(
        Long id,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectDto from(Project p) {
        return new ProjectDto(p.getId(), p.getName(), p.getDescription(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
