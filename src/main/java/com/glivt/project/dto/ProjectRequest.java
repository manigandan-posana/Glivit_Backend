package com.glivt.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 512) String description,
        @Size(max = 16) String status) {
}
