package com.glivt.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record CompanyCodeRequest(@NotBlank String companyCode) {
}
