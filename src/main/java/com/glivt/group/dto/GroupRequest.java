package com.glivt.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroupRequest(
        @NotBlank @Size(max = 160) String name,
        Long parentId,
        Long managerId) {
}
