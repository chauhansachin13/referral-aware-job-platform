package com.referralhub.dedup.title;

/**
 * A single ladder that every company's own ladder is projected onto.
 *
 * <p>The ordinal is meaningful: {@link #distance} is what lets the scorer treat "Senior Software
 * Engineer" and "Staff Software Engineer" as close but not identical, while "Intern" and
 * "Director" are as far apart as the ladder goes.
 */
public enum SeniorityLevel {

    INTERN,
    ENTRY,
    MID,
    SENIOR,
    STAFF,
    PRINCIPAL,
    MANAGER,
    DIRECTOR,
    VP,
    /** No level stated. Most postings are this, and it must not be treated as a mismatch. */
    UNSPECIFIED;

    public int distance(SeniorityLevel other) {
        if (this == UNSPECIFIED || other == UNSPECIFIED) {
            return 0;
        }
        return Math.abs(ordinal() - other.ordinal());
    }

    public boolean isIndividualContributor() {
        return ordinal() <= PRINCIPAL.ordinal() && this != UNSPECIFIED;
    }
}
