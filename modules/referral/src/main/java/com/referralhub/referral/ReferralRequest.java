package com.referralhub.referral;

import com.referralhub.referral.state.ReferralState;
import java.time.Instant;
import java.util.UUID;

public record ReferralRequest(
        UUID id,
        UUID seekerId,
        UUID referrerId,
        UUID canonicalJobId,
        UUID companyId,
        UUID resumeId,
        ReferralState state,
        String message,
        String declineReason,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant submittedAt,
        Instant closedAt) {

    public boolean isExpiredAt(Instant now) {
        return !state.isTerminal() && expiresAt.isBefore(now);
    }
}
