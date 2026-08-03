package com.glivt.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create Tenant payload.
 *
 * <p>Bean validation covers shape (required, length, format); uniqueness of the
 * tenant id, tenant name and admin email is checked in {@code TenantAdminService}
 * and backed by database unique keys, because shape validation cannot see other
 * rows.
 */
public record TenantCreateRequest(
        @NotBlank(message = "Tenant name is required")
        @Size(max = 160, message = "Tenant name must be 160 characters or fewer")
        String name,

        @NotBlank(message = "Company name is required")
        @Size(max = 160, message = "Company name must be 160 characters or fewer")
        String companyName,

        @NotBlank(message = "Admin name is required")
        @Size(max = 160, message = "Admin name must be 160 characters or fewer")
        String adminName,

        @NotBlank(message = "Admin email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 160, message = "Admin email must be 160 characters or fewer")
        String adminEmail,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "Enter a valid phone number (8-20 digits, optional +)")
        String adminPhone,

        @NotBlank(message = "Tenant status is required")
        @Pattern(regexp = "ACTIVE|DISABLED|MAINTENANCE", message = "Unknown tenant status")
        String status) {
}
