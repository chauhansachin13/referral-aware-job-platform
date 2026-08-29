package com.referralhub.referral.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The referral lifecycle, with its legal transitions declared rather than implied.
 *
 * <p>Encoding the graph here — instead of as scattered {@code if} statements in a controller —
 * is what makes "a referrer cannot submit a referral they never accepted" a property of the
 * domain rather than of whichever endpoint happens to be called. Every path into the aggregate
 * goes through {@link #transitionTo}, so a new API surface, a Kafka consumer or an admin script
 * all get the same rules for free.
 */
public enum ReferralState {

    /** The seeker has asked. No referrer has committed. */
    REQUESTED,

    /** A verified employee took it on. This is the point resume access opens. */
    ACCEPTED,

    /** The referrer put the candidate into their internal system. */
    SUBMITTED,

    /** Terminal: the loop is finished, successfully or not. */
    CLOSED,

    /** Terminal: the referrer said no. */
    DECLINED,

    /** Terminal: nobody acted in time. */
    EXPIRED;

    private static final Map<ReferralState, Set<ReferralState>> ALLOWED = Map.of(
            REQUESTED, EnumSet.of(ACCEPTED, DECLINED, EXPIRED),
            // A referrer who accepted may still fail to submit; that path expires rather than
            // hanging forever, and CLOSED is reachable directly so a seeker can withdraw.
            ACCEPTED, EnumSet.of(SUBMITTED, EXPIRED, CLOSED),
            SUBMITTED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(ReferralState.class),
            DECLINED, EnumSet.noneOf(ReferralState.class),
            EXPIRED, EnumSet.noneOf(ReferralState.class));

    public Set<ReferralState> allowedNext() {
        return ALLOWED.get(this);
    }

    public boolean canTransitionTo(ReferralState next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Whether the resume may be released to the referrer in this state. */
    public boolean grantsResumeAccess() {
        return this == ACCEPTED || this == SUBMITTED;
    }

    /**
     * @throws IllegalTransitionException if the move is not on the graph
     */
    public ReferralState transitionTo(ReferralState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalTransitionException(this, next);
        }
        return next;
    }
}
