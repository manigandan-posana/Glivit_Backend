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
 * rows. Password confirmation is validated on both sides: the client shows the
 * inline message, the server refuses the request if they disagree.
 */
public record TenantCreateRequest(
        @NotBlank(message = "Tenant name is required")
        @Size(max = 160, message = "Tenant name must be 160 characters or fewer")
        String name,

        @NotBlank(message = "Tenant ID is required")
        @Size(min = 3, max = 64, message = "Tenant ID must be between 3 and 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
                message = "Tenant ID may only contain letters, numbers, dot, dash and underscore")
        String tenantId,

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

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "Confirm the password")
        String confirmPassword,

        @NotBlank(message = "Tenant status is required")
        @Pattern(regexp = "ACTIVE|DISABLED|MAINTENANCE", message = "Unknown tenant status")
        String status) {
}
