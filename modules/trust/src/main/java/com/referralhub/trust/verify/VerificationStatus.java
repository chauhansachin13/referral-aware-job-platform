package com.referralhub.trust.verify;

public enum VerificationStatus {
    PENDING,
    VERIFIED,
    /** The lease ran out; re-verification is required before referring again. */
    EXPIRED,
    /** Too many wrong codes, or the domain stopped matching the company. */
    REVOKED
}
