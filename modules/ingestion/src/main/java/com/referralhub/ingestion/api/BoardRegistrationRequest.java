package com.referralhub.ingestion.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Registers a company's board with the crawler. */
public record BoardRegistrationRequest(
        @NotBlank String companyName,
        @NotBlank @Pattern(regexp = "[a-z0-9-]{2,64}", message = "must be a lowercase slug")
        String companySlug,
        @NotBlank @Pattern(regexp = "greenhouse|lever|ashby",
                message = "must be one of the supported public ATS boards")
        String source,
        @NotBlank String boardToken,
        String emailDomain,
        String careersUrl) {
}
