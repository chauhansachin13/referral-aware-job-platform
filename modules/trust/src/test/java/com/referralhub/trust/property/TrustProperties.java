package com.referralhub.trust.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.trust.reputation.ReputationScore;
import com.referralhub.trust.verify.OneTimeCodes;
import com.referralhub.trust.verify.WorkEmails;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class TrustProperties {

    @Property(tries = 500)
    void wilsonBoundIsAlwaysAProbability(@ForAll @IntRange(min = -10, max = 1_000) int successes,
                                         @ForAll @IntRange(min = 0, max = 1_000) int trials) {
        double bound = ReputationScore.wilsonLowerBound(successes, trials);

        assertThat(bound).isBetween(0.0, 1.0);
        assertThat(Double.isFinite(bound)).isTrue();
    }

    @Property(tries = 300)
    void moreSuccessesNeverLowersTheBound(@ForAll @IntRange(min = 1, max = 500) int trials,
                                          @ForAll @IntRange(min = 0, max = 499) int successes) {
        int bounded = Math.min(successes, trials);
        if (bounded >= trials) {
            return;
        }
        assertThat(ReputationScore.wilsonLowerBound(bounded + 1, trials))
                .isGreaterThanOrEqualTo(ReputationScore.wilsonLowerBound(bounded, trials));
    }

    @Property(tries = 300)
    void theBoundNeverExceedsTheObservedRate(@ForAll @IntRange(min = 1, max = 500) int trials,
                                             @ForAll @IntRange(min = 0, max = 500) int successes) {
        int bounded = Math.min(successes, trials);

        // It is a *lower* bound; exceeding the point estimate would defeat its purpose.
        assertThat(ReputationScore.wilsonLowerBound(bounded, trials))
                .isLessThanOrEqualTo((double) bounded / trials + 1e-9);
    }

    @Property(tries = 300)
    void combinedScoreIsAlwaysBounded(@ForAll @IntRange(min = 0, max = 500) int received,
                                      @ForAll @IntRange(min = 0, max = 500) int responded,
                                      @ForAll @IntRange(min = 0, max = 500) int accepted,
                                      @ForAll @IntRange(min = 0, max = 500) int completed,
                                      @ForAll @IntRange(min = 0, max = 500) int expired) {
        double score = ReputationScore.of(new ReputationScore.Counters(
                received, responded, accepted, completed, expired));

        assertThat(score).isBetween(0.0, 1.0);
    }

    @Property(tries = 300)
    void anIssuedCodeAlwaysVerifiesAndIsNeverStoredInTheClear() {
        OneTimeCodes.Issued issued = OneTimeCodes.issue();

        assertThat(OneTimeCodes.matches(issued.plaintext(), issued.hash(), issued.salt())).isTrue();
        assertThat(issued.hash()).doesNotContain(issued.plaintext());
        assertThat(issued.salt()).doesNotContain(issued.plaintext());
    }

    @Property(tries = 500)
    void domainMatchingNeverThrowsAndNeverAcceptsARandomString(@ForAll String candidate,
                                                               @ForAll String companyDomain) {
        boolean matched = WorkEmails.belongsToCompany(candidate, companyDomain);

        if (matched) {
            // Anything accepted must at least be a well-formed non-consumer address.
            assertThat(WorkEmails.isWellFormed(candidate)).isTrue();
            assertThat(WorkEmails.isConsumerDomain(WorkEmails.domainOf(candidate))).isFalse();
        }
    }

    @Property(tries = 300)
    void aSuffixMatchIsNeverEnoughOnItsOwn(@ForAll @IntRange(min = 1, max = 12) int prefixLength) {
        String attacker = "a".repeat(prefixLength) + "acme.com";

        // "evilacme.com" ends with "acme.com" but is a different domain entirely.
        assertThat(WorkEmails.belongsToCompany("someone@" + attacker, "acme.com")).isFalse();
    }
}
