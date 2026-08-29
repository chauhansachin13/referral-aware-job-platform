package com.referralhub.common.events;

import java.time.Instant;
import java.util.UUID;

/** Emitted on every accepted referral-request state transition. */
public record ReferralLifecycleChanged(
        UUID requestId,
        UUID seekerId,
        UUID canonicalJobId,
        UUID referrerId,
        String fromState,
        String toState,
        String actor,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "referral." + toState.toLowerCase();
    }

    @Override
    public String aggregateType() {
        return "referral_request";
    }

    @Override
    public UUID aggregateId() {
        return requestId;
    }

    @Override
    public String topic() {
        return Topics.REFERRALS_LIFECYCLE;
    }
}
