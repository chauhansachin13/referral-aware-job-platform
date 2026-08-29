package com.referralhub.trust.verify;

import java.time.Instant;
import java.util.UUID;

public record EmployeeVerification(
        UUID id,
        UUID userId,
        UUID companyId,
        String workEmail,
        String emailDomain,
        VerificationStatus status,
        Instant verifiedAt,
        Instant expiresAt,
        Instant otpExpiresAt,
        int otpAttempts) {

    public boolean isActiveAt(Instant now) {
        return status == VerificationStatus.VERIFIED
                && expiresAt != null
                && expiresAt.isAfter(now);
    }
}
