package com.glivt.group.dto;

import com.glivt.group.DeviceGroup;
import java.time.Instant;

public record GroupDto(
        Long id,
        String name,
        Long parentId,
        Long managerId,
        Instant createdAt,
        Instant updatedAt) {

    public static GroupDto from(DeviceGroup group) {
        return new GroupDto(group.getId(), group.getName(), group.getParentId(),
                group.getManagerId(), group.getCreatedAt(), group.getUpdatedAt());
    }
}
