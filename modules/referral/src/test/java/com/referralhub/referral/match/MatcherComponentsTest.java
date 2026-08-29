package com.referralhub.referral.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The matcher's scoring helpers, exercised directly.
 *
 * <p>Added because mutation testing showed these branches were only reachable through the final
 * assignment, where the fairness penalty and the capacity short-circuit mask a wrong component
 * score: several different constants produce the same set of assignments on any given example.
 */
class MatcherComponentsTest {

    private static final MatchingWeights WEIGHTS = MatchingWeights.defaults();

    private static ReferralMatcher.PendingRequest request(String department, String level,
                                                          Set<String> stack) {
        return new ReferralMatcher.PendingRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), department, level, stack, Instant.parse("2026-08-01T00:00:00Z"));
    }

    private static ReferralMatcher.EligibleReferrer referrer(String department, String level,
                                                             Set<String> stack, double reputation) {
        return new ReferralMatcher.EligibleReferrer(UUID.randomUUID(), department, level, stack,
                reputation, 5);
    }

    @ParameterizedTest(name = "role {0}, referrer {1} -> {2}")
    @DisplayName("seniority fit peaks at or one rung above the role and tails off both ways")
    @CsvSource({
            "SENIOR,    SENIOR,     1.00",
            "SENIOR,    STAFF,      1.00",
            "SENIOR,    PRINCIPAL,  0.75",
            "MID,       PRINCIPAL,  0.50",
            "ENTRY,     VP,         0.25",
            "SENIOR,    MID,        0.25",
            "SENIOR,    ENTRY,      0.00",
            "STAFF,     ENTRY,      0.00",
            "SENIOR,    UNKNOWN,    0.50",
            "UNKNOWN,   SENIOR,     0.50"
    })
    void seniorityFitFollowsTheSchedule(String roleLevel, String referrerLevel, double expected) {
        assertThat(ReferralMatcher.seniorityFit(roleLevel, referrerLevel))
                .isEqualTo(expected, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a referrer far above the role fits worse than one just above")
    void beingTooSeniorIsNotBetter() {
        // A VP referring a new grad carries less internal weight than their future teammate,
        // and spends a scarcer person's time.
        assertThat(ReferralMatcher.seniorityFit("ENTRY", "MID"))
                .isGreaterThan(ReferralMatcher.seniorityFit("ENTRY", "VP"));
    }

    @ParameterizedTest
    @DisplayName("an unrecognised ladder is neutral rather than penalised")
    @NullAndEmptySource
    @ValueSource(strings = {"L7", "band-3", "  "})
    void unknownLevelsAreNeutral(String level) {
        assertThat(ReferralMatcher.seniorityFit("SENIOR", level)).isEqualTo(0.5);
        assertThat(ReferralMatcher.seniorityFit(level, "SENIOR")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("stack overlap ignores case and surrounding whitespace")
    void stackComparisonIsNormalized() {
        assertThat(ReferralMatcher.jaccard(Set.of("Java", " Kafka "), Set.of("java", "kafka")))
                .isEqualTo(1.0);
        assertThat(ReferralMatcher.jaccard(Set.of("JAVA"), Set.of("java", "go")))
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("an empty or null stack contributes nothing rather than throwing")
    void emptyStacksAreZero() {
        assertThat(ReferralMatcher.jaccard(Set.of(), Set.of("java"))).isZero();
        assertThat(ReferralMatcher.jaccard(Set.of("java"), Set.of())).isZero();
        assertThat(ReferralMatcher.jaccard(null, Set.of("java"))).isZero();
        assertThat(ReferralMatcher.jaccard(Set.of("java"), null)).isZero();
    }

    @Test
    @DisplayName("blank entries are discarded before comparison")
    void blankTechIsIgnored() {
        Set<String> withBlanks = new HashSet<>();
        withBlanks.add("java");
        withBlanks.add("   ");
        withBlanks.add("");

        assertThat(ReferralMatcher.jaccard(withBlanks, Set.of("java"))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("jaccard is symmetric")
    void jaccardIsSymmetric() {
        Set<String> left = Set.of("java", "kafka", "postgres");
        Set<String> right = Set.of("java", "go");

        assertThat(ReferralMatcher.jaccard(left, right))
                .isEqualTo(ReferralMatcher.jaccard(right, left));
    }

    @Test
    @DisplayName("each affinity signal moves the score in the direction its weight implies")
    void everySignalContributes() {
        ReferralMatcher.PendingRequest role =
                request("Payments", "SENIOR", Set.of("java", "kafka"));

        double perfect = ReferralMatcher.affinity(role,
                referrer("Payments", "STAFF", Set.of("java", "kafka"), 1.0), WEIGHTS);
        double wrongOrg = ReferralMatcher.affinity(role,
                referrer("Search", "STAFF", Set.of("java", "kafka"), 1.0), WEIGHTS);
        double wrongStack = ReferralMatcher.affinity(role,
                referrer("Payments", "STAFF", Set.of("figma"), 1.0), WEIGHTS);
        double wrongLevel = ReferralMatcher.affinity(role,
                referrer("Payments", "ENTRY", Set.of("java", "kafka"), 1.0), WEIGHTS);
        double unresponsive = ReferralMatcher.affinity(role,
                referrer("Payments", "STAFF", Set.of("java", "kafka"), 0.0), WEIGHTS);

        assertThat(perfect).isEqualTo(1.0, Offset.offset(1e-9));
        assertThat(wrongOrg).isLessThan(perfect);
        assertThat(wrongStack).isLessThan(perfect);
        assertThat(wrongLevel).isLessThan(perfect);
        assertThat(unresponsive).isLessThan(perfect);

        // Org and responsiveness are deliberately weighted the same (0.30 each): being on the
        // team and answering at all are equally necessary, and neither substitutes for the other.
        assertThat(unresponsive).isEqualTo(wrongOrg, Offset.offset(1e-9));

        // Stack (0.25) outweighs seniority (0.15), so a stack mismatch costs more than a
        // one-rung ladder gap.
        assertThat(wrongStack).isLessThan(
                ReferralMatcher.affinity(role,
                        referrer("Payments", "PRINCIPAL", Set.of("java", "kafka"), 1.0), WEIGHTS));
    }

    @Test
    @DisplayName("a department comparison is case-insensitive but never matches on blank")
    void departmentMatching() {
        ReferralMatcher.PendingRequest role = request("Payments", "SENIOR", Set.of());

        double sameCase = ReferralMatcher.affinity(role,
                referrer("Payments", "SENIOR", Set.of(), 0.5), WEIGHTS);
        double otherCase = ReferralMatcher.affinity(role,
                referrer("payments", "SENIOR", Set.of(), 0.5), WEIGHTS);
        double blank = ReferralMatcher.affinity(role,
                referrer("", "SENIOR", Set.of(), 0.5), WEIGHTS);

        assertThat(otherCase).isEqualTo(sameCase);
        assertThat(blank).isLessThan(sameCase);
    }

    @Test
    @DisplayName("a reputation outside [0,1] is clamped rather than distorting the score")
    void reputationIsClamped() {
        ReferralMatcher.PendingRequest role = request("Payments", "SENIOR", Set.of("java"));

        double atCeiling = ReferralMatcher.affinity(role,
                referrer("Payments", "SENIOR", Set.of("java"), 1.0), WEIGHTS);
        double aboveCeiling = ReferralMatcher.affinity(role,
                referrer("Payments", "SENIOR", Set.of("java"), 7.5), WEIGHTS);
        double atFloor = ReferralMatcher.affinity(role,
                referrer("Payments", "SENIOR", Set.of("java"), 0.0), WEIGHTS);
        double belowFloor = ReferralMatcher.affinity(role,
                referrer("Payments", "SENIOR", Set.of("java"), -3.0), WEIGHTS);

        assertThat(aboveCeiling).isEqualTo(atCeiling);
        assertThat(belowFloor).isEqualTo(atFloor);
    }

    @Test
    @DisplayName("the weights sum to one, so affinity is a proportion of a perfect match")
    void weightsAreNormalized() {
        assertThat(WEIGHTS.affinitySum()).isEqualTo(1.0, Offset.offset(1e-9));
    }
}
