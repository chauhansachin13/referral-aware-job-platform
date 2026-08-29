package com.referralhub.referral.state;

import com.referralhub.common.error.DomainException;

public class IllegalTransitionException extends DomainException {

    private final ReferralState from;
    private final ReferralState to;

    public IllegalTransitionException(ReferralState from, ReferralState to) {
        super("illegal_transition", "A referral cannot go from " + from + " to " + to
                + "; allowed from " + from + ": " + from.allowedNext());
        this.from = from;
        this.to = to;
    }

    public ReferralState from() {
        return from;
    }

    public ReferralState to() {
        return to;
    }
}
