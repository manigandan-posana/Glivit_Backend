package com.glivt.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record DriverRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 64) String identifier,
        @Pattern(regexp = "^[+0-9 ()-]{0,32}$", message = "Invalid phone number") String phone,
        @Size(max = 64) String licenceNumber,
        LocalDate licenceExpiry,
        Long projectId,
        @Pattern(regexp = "^[+0-9 ()-]{0,32}$", message = "Invalid emergency contact")
        String emergencyContact,
        Boolean active) {
}
