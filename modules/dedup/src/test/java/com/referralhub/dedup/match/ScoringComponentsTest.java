package com.referralhub.dedup.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.dedup.config.DedupProperties;
import com.referralhub.dedup.minhash.MinHasher;
import com.referralhub.dedup.title.CanonicalTitle;
import com.referralhub.dedup.title.SeniorityLevel;
import com.referralhub.dedup.title.TitleNormalizer;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each gate and scoring component on its own, branch by branch.
 *
 * <p>Added after mutation testing showed that these branches were reachable only through the
 * end-to-end score, where several different wrong constants produce the same final decision on
 * the labelled pairs. That is precisely the situation where a threshold can be silently wrong:
 * the suite is green, the aggregate looks right, and one boundary is off by a rung.
 */
class ScoringComponentsTest {

    private static final DedupProperties PROPERTIES = new DedupProperties();
    private static final DuplicateScorer SCORER = new DuplicateScorer(PROPERTIES);
    private static final MinHasher HASHER =
            new MinHasher(PROPERTIES.getNumHashes(), PROPERTIES.getHashSeed());
    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static JobFingerprint job(String title, String location, boolean remote) {
        return JobFingerprint.of(UUID.randomUUID(), COMPANY, title,
                "Own the ledger end to end.", location, remote, HASHER, PROPERTIES.getShingleSize());
    }

    @Nested
    @DisplayName("levelGate")
    class LevelGate {

        @ParameterizedTest(name = "{0} vs {1} -> {2}")
        @DisplayName("the ladder gap maps onto a fixed schedule of multipliers")
        @CsvSource({
                "SENIOR,    SENIOR,     1.00",
                "SENIOR,    STAFF,      0.85",
                "ENTRY,     MID,        0.85",
                "ENTRY,     SENIOR,     0.35",
                "SENIOR,    PRINCIPAL,  0.35",
                "INTERN,    MID,        0.35",
                "INTERN,    SENIOR,     0.00",
                "ENTRY,     PRINCIPAL,  0.00"
        })
        void mapsLadderDistance(SeniorityLevel left, SeniorityLevel right, double expected) {
            assertThat(DuplicateScorer.levelGate(left, right))
                    .isEqualTo(expected, Offset.offset(1e-9));
            assertThat(DuplicateScorer.levelGate(right, left))
                    .as("the gate must be symmetric").isEqualTo(expected, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("an unstated level is missing information, never a mismatch")
        void unspecifiedIsNeutral() {
            for (SeniorityLevel level : SeniorityLevel.values()) {
                assertThat(DuplicateScorer.levelGate(SeniorityLevel.UNSPECIFIED, level))
                        .isEqualTo(1.0);
                assertThat(DuplicateScorer.levelGate(level, SeniorityLevel.UNSPECIFIED))
                        .isEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("a manager and an individual contributor are never the same job")
        void managementLadderIsSeparate() {
            assertThat(DuplicateScorer.levelGate(SeniorityLevel.MANAGER, SeniorityLevel.SENIOR))
                    .isZero();
            assertThat(DuplicateScorer.levelGate(SeniorityLevel.MANAGER, SeniorityLevel.PRINCIPAL))
                    .as("adjacent ordinals, but one manages and one does not").isZero();
            assertThat(DuplicateScorer.levelGate(SeniorityLevel.MANAGER, SeniorityLevel.DIRECTOR))
                    .as("both manage, one rung apart").isEqualTo(0.85, Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("locationGate")
    class LocationGate {

        @Test
        @DisplayName("remote and on-site are different requisitions, whatever else matches")
        void remoteMismatchIsFatal() {
            JobFingerprint remote = job("Senior Software Engineer", "Remote", true);
            JobFingerprint onsite = job("Senior Software Engineer", "Berlin, Germany", false);

            assertThat(DuplicateScorer.locationGate(remote, onsite, 1.0)).isZero();
        }

        @Test
        @DisplayName("a strong location match passes the gate untouched")
        void strongMatchIsUngated() {
            JobFingerprint a = job("Engineer", "Berlin", false);
            JobFingerprint b = job("Engineer", "Berlin, Germany", false);

            assertThat(DuplicateScorer.locationGate(a, b, 0.8)).isEqualTo(1.0);
            assertThat(DuplicateScorer.locationGate(a, b, 1.0)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a missing location is discounted, not rejected")
        void blankLocationIsDiscounted() {
            JobFingerprint stated = job("Engineer", "Berlin", false);
            JobFingerprint blank = job("Engineer", "", false);

            assertThat(DuplicateScorer.locationGate(stated, blank, 0.3))
                    .isEqualTo(0.9, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("a partial match is discounted more than a strong one")
        void partialMatchIsDiscounted() {
            JobFingerprint a = job("Engineer", "Berlin Germany", false);
            JobFingerprint b = job("Engineer", "Munich Germany", false);

            assertThat(DuplicateScorer.locationGate(a, b, 0.5))
                    .isEqualTo(0.85, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("two different cities collapse the score rather than nudging it")
        void disjointCitiesAreHeavilyGated() {
            JobFingerprint a = job("Engineer", "Berlin", false);
            JobFingerprint b = job("Engineer", "Tokyo", false);

            assertThat(DuplicateScorer.locationGate(a, b, 0.1))
                    .isEqualTo(0.25, Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("locationScore")
    class LocationScore {

        @Test
        @DisplayName("two remote postings match regardless of how remoteness is worded")
        void bothRemoteIsAFullMatch() {
            assertThat(DuplicateScorer.locationScore(
                    job("Engineer", "Remote - United States", true),
                    job("Engineer", "Anywhere", true))).isEqualTo(1.0);
        }

        @Test
        @DisplayName("two unstated locations are unknown, scored halfway, not zero")
        void bothBlankIsNeutral() {
            assertThat(DuplicateScorer.locationScore(job("Engineer", "", false),
                    job("Engineer", "", false))).isEqualTo(0.5);
        }

        @Test
        @DisplayName("one unstated location is weaker evidence than two")
        void oneBlankIsWeaker() {
            assertThat(DuplicateScorer.locationScore(job("Engineer", "Berlin", false),
                    job("Engineer", "", false))).isEqualTo(0.3);
        }

        @Test
        @DisplayName("identical strings score 1, containment scores 0.8")
        void exactAndContainment() {
            assertThat(DuplicateScorer.locationScore(job("Engineer", "Berlin", false),
                    job("Engineer", "Berlin", false))).isEqualTo(1.0);
            assertThat(DuplicateScorer.locationScore(job("Engineer", "san francisco", false),
                    job("Engineer", "san francisco ca united states", false))).isEqualTo(0.8);
        }

        @Test
        @DisplayName("a shared token scores above a disjoint pair but below containment")
        void sharedTokenIsPartial() {
            double shared = DuplicateScorer.locationScore(job("Engineer", "Berlin Germany", false),
                    job("Engineer", "Munich Germany", false));
            double disjoint = DuplicateScorer.locationScore(job("Engineer", "Berlin", false),
                    job("Engineer", "Tokyo", false));

            assertThat(shared).isBetween(0.4, 0.8);
            assertThat(disjoint).isEqualTo(0.1);
            assertThat(shared).isGreaterThan(disjoint);
        }
    }

    @Nested
    @DisplayName("titleScore")
    class TitleScore {

        private static CanonicalTitle title(String raw) {
            return TitleNormalizer.normalize(raw);
        }

        @Test
        @DisplayName("identical titles score 1")
        void identicalTitlesAreOne() {
            assertThat(DuplicateScorer.titleScore(title("Senior Software Engineer, Payments"),
                    title("Senior Software Engineer, Payments")))
                    .isEqualTo(1.0, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("a different specialization costs exactly the specialization weight")
        void specializationIsWorthTwoTenths() {
            double same = DuplicateScorer.titleScore(title("Senior Software Engineer, Payments"),
                    title("Senior Software Engineer, Payments"));
            double different = DuplicateScorer.titleScore(title("Senior Software Engineer, Payments"),
                    title("Senior Software Engineer, Search"));

            assertThat(same - different).isEqualTo(0.2, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("a level gap costs proportionally, and three rungs costs all of it")
        void levelGapIsProportional() {
            double oneRung = DuplicateScorer.titleScore(title("Senior Software Engineer"),
                    title("Staff Software Engineer"));
            double threeRungs = DuplicateScorer.titleScore(title("Software Engineer I"),
                    title("Staff Software Engineer"));

            assertThat(oneRung).isEqualTo(1.0 - 0.3 / 3.0, Offset.offset(1e-9));
            assertThat(threeRungs).isEqualTo(0.7, Offset.offset(1e-9));
        }

        @Test
        @DisplayName("two blank specializations agree; one blank does not")
        void blankSpecializationsAgree() {
            assertThat(DuplicateScorer.titleScore(title("Software Engineer"),
                    title("Software Engineer"))).isEqualTo(1.0, Offset.offset(1e-9));

            assertThat(DuplicateScorer.titleScore(title("Software Engineer"),
                    title("Software Engineer, Payments"))).isLessThan(1.0);
        }

        @Test
        @DisplayName("an unrelated role family scores well below a matching one")
        void differentRolesScoreLower() {
            assertThat(DuplicateScorer.titleScore(title("Backend Engineer"), title("Product Manager")))
                    .isLessThan(DuplicateScorer.titleScore(title("Backend Engineer"),
                            title("Backend Engineer")));
        }
    }

    @Test
    @DisplayName("the company gate is binary, with no partial credit")
    void companyGateIsBinary() {
        assertThat(DuplicateScorer.companyGate(1.0)).isEqualTo(1.0);
        assertThat(DuplicateScorer.companyGate(0.999)).isZero();
        assertThat(DuplicateScorer.companyGate(0.0)).isZero();
    }

    @Test
    @DisplayName("a gate of zero collapses the total however strong everything else is")
    void aZeroGateCollapsesTheTotal() {
        JobFingerprint here = job("Senior Software Engineer, Payments", "Berlin", false);
        JobFingerprint elsewhere = JobFingerprint.of(UUID.randomUUID(),
                UUID.fromString("00000000-0000-0000-0000-0000000000c2"),
                "Senior Software Engineer, Payments", "Own the ledger end to end.",
                "Berlin", false, HASHER, PROPERTIES.getShingleSize());

        MatchScore score = SCORER.score(here, elsewhere);

        assertThat(score.jaccard()).isEqualTo(1.0);
        assertThat(score.titleScore()).isEqualTo(1.0, Offset.offset(1e-9));
        assertThat(score.total()).as("a perfect text match at another company is still not a merge")
                .isZero();
    }
}
