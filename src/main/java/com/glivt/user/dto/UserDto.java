package com.glivt.user.dto;

import com.glivt.security.Permissions;
import com.glivt.user.Role;
import com.glivt.user.User;
import com.glivt.user.UserStatus;
import java.time.Instant;
import java.util.Map;

public record UserDto(
        Long id,
        String username,
        String name,
        String email,
        String mobile,
        String address,
        Role role,
        Long managerId,
        UserStatus status,
        Instant accountExpiry,
        Map<String, Boolean> permissions,
        Instant createdAt,
        Instant updatedAt) {

    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getName(), user.getEmail(),
                user.getMobile(), user.getAddress(), user.getRole(), user.getManagerId(),
                user.getStatus(), user.getAccountExpiry(),
                Permissions.forUser(user.getRole(), user.getPermissions()).asMap(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
