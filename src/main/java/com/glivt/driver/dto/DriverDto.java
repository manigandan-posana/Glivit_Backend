package com.glivt.driver.dto;

import com.glivt.driver.Driver;
import java.time.Instant;
import java.time.LocalDate;

public record DriverDto(
        Long id,
        Long projectId,
        String name,
        String identifier,
        String phone,
        String licenceNumber,
        LocalDate licenceExpiry,
        String emergencyContact,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static DriverDto from(Driver d) {
        return new DriverDto(d.getId(), d.getProjectId(), d.getName(), d.getIdentifier(),
                d.getPhone(), d.getLicenceNumber(), d.getLicenceExpiry(),
                d.getEmergencyContact(), d.isActive(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
