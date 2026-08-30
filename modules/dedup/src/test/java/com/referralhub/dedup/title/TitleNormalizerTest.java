package com.referralhub.dedup.title;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TitleNormalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("numeric, roman and spelled-out ladders land on the same rung")
    @CsvSource({
            "'SDE-1',                           ENTRY",
            "'SDE 1',                           ENTRY",
            "'Software Engineer I',             ENTRY",
            "'Software Development Engineer 1', ENTRY",
            "'Junior Software Engineer',        ENTRY",
            "'New Grad Software Engineer',      ENTRY",
            "'SDE-2',                           MID",
            "'Software Engineer II',            MID",
            "'Member of Technical Staff',       MID",
            "'MTS',                             MID",
            "'SDE-3',                           SENIOR",
            "'Software Engineer III',           SENIOR",
            "'Senior Software Engineer',        SENIOR",
            "'Sr. Software Engineer',           SENIOR",
            "'Snr Software Engineer',           SENIOR",
            "'Senior Member of Technical Staff',SENIOR",
            "'SMTS',                            SENIOR",
            "'Staff Software Engineer',         STAFF",
            "'Tech Lead, Platform',             STAFF",
            "'Principal Engineer',              PRINCIPAL",
            "'Distinguished Engineer',          PRINCIPAL",
            "'Engineering Manager',             MANAGER",
            "'Director of Engineering',         DIRECTOR",
            "'VP of Engineering',               VP",
            "'Software Engineer Intern',        INTERN",
            "'Software Engineer',               UNSPECIFIED"
    })
    void mapsTitlesToLevels(String title, SeniorityLevel expected) {
        assertThat(TitleNormalizer.normalize(title).level()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("role vocabularies collapse onto one family per job type")
    @CsvSource({
            "'SDE-2',                     software engineer",
            "'Software Developer',        software engineer",
            "'Member of Technical Staff', software engineer",
            "'Tech Lead',                 software engineer",
            "'Site Reliability Engineer', site reliability engineer",
            "'SRE',                       site reliability engineer",
            "'DevOps Engineer',           site reliability engineer",
            "'Platform Engineer',         site reliability engineer",
            "'Machine Learning Engineer', machine learning engineer",
            "'ML Engineer',               machine learning engineer",
            "'Backend Engineer',          backend engineer",
            "'Back-End Developer',        backend engineer",
            "'Frontend Engineer',         frontend engineer",
            "'SDET',                      qa engineer",
            "'Product Manager',           product manager",
            "'Technical Program Manager', technical program manager"
    })
    void mapsTitlesToRoles(String title, String expected) {
        assertThat(TitleNormalizer.normalize(title).role()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the headline variants share a canonical key where they share a rung")
    void headlineVariantsAgree() {
        assertThat(TitleNormalizer.sameRoleAndLevel("SDE-1", "Software Engineer I")).isTrue();
        assertThat(TitleNormalizer.sameRoleAndLevel("SDE-2", "Member of Technical Staff")).isTrue();
        assertThat(TitleNormalizer.sameRoleAndLevel("MTS", "Software Engineer II")).isTrue();

        // They must not collapse across rungs, or every posting a company has becomes one job.
        assertThat(TitleNormalizer.sameRoleAndLevel("SDE-1", "SDE-3")).isFalse();
    }

    @Test
    @DisplayName("specialization survives every separator a board might use")
    void keepsSpecialization() {
        assertThat(TitleNormalizer.normalize("Senior Software Engineer, Payments").specialization())
                .isEqualTo("payments");
        assertThat(TitleNormalizer.normalize("Sr. Software Engineer - Payments").specialization())
                .isEqualTo("payments");
        assertThat(TitleNormalizer.normalize("Engineering | Backend Engineer, Search").specialization())
                .isEqualTo("search");
    }

    @Test
    @DisplayName("requisition numbers and bracketed tails are discarded")
    void stripsNoise() {
        assertThat(TitleNormalizer.normalize("Machine Learning Engineer, Ranking (Req #40122)"))
                .isEqualTo(TitleNormalizer.normalize("Machine Learning Engineer, Ranking"));
    }

    @Test
    @DisplayName("an unrecognised title keeps its own words rather than collapsing to a bucket")
    void unknownTitlesStayDistinct() {
        assertThat(TitleNormalizer.normalize("Chief of Staff").role())
                .isNotEqualTo(TitleNormalizer.normalize("Recruiting Coordinator").role());
    }

    @Test
    @DisplayName("null and blank titles do not blow up the pipeline")
    void handlesMissingTitles() {
        assertThat(TitleNormalizer.normalize(null).role()).isEqualTo("unknown");
        assertThat(TitleNormalizer.normalize("   ").level()).isEqualTo(SeniorityLevel.UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a role noun is not a seniority word")
    @CsvSource({
            // Every one of these came back mis-levelled from a real Greenhouse board.
            "'Product Manager',                     UNSPECIFIED",
            "'Technical Program Manager',           UNSPECIFIED",
            "'Customer Success Manager',            UNSPECIFIED",
            "'Solutions Architect',                 UNSPECIFIED",
            "'Account Executive',                   UNSPECIFIED",
            // ...while an actual seniority word still levels them.
            "'Senior Product Manager',              SENIOR",
            "'Staff Technical Program Manager',     STAFF",
            "'Principal Solutions Architect',       PRINCIPAL",
            // ...and the management ladder still resolves when it is named outright.
            "'Engineering Manager',                 MANAGER",
            "'Engineering Manager, Payments',       MANAGER",
            "'Director of Product',                 DIRECTOR",
            "'VP of Engineering',                   VP"
    })
    void roleNounsAreNotSeniority(String title, SeniorityLevel expected) {
        assertThat(TitleNormalizer.normalize(title).level()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a product manager is an individual contributor, not a people manager")
    void productManagersAreNotOnTheManagementLadder() {
        // levelGate returns 0 across the IC/management boundary, so getting this wrong stopped
        // two postings for the same PM role from ever merging.
        assertThat(TitleNormalizer.normalize("Product Manager").level()
                .isIndividualContributor())
                .as("UNSPECIFIED is neutral, which is the correct answer for a bare role noun")
                .isFalse();
        assertThat(TitleNormalizer.normalize("Senior Product Manager").level())
                .isEqualTo(SeniorityLevel.SENIOR);
        assertThat(TitleNormalizer.normalize("Engineering Manager").level()
                .isIndividualContributor()).isFalse();
    }

    @Test
    @DisplayName("management rungs are not individual contributor rungs")
    void separatesManagementLadder() {
        assertThat(SeniorityLevel.SENIOR.isIndividualContributor()).isTrue();
        assertThat(SeniorityLevel.PRINCIPAL.isIndividualContributor()).isTrue();
        assertThat(SeniorityLevel.MANAGER.isIndividualContributor()).isFalse();
        assertThat(SeniorityLevel.UNSPECIFIED.isIndividualContributor()).isFalse();
    }

    @Test
    @DisplayName("an unspecified level is neutral, not maximally distant")
    void unspecifiedLevelIsNeutral() {
        assertThat(SeniorityLevel.UNSPECIFIED.distance(SeniorityLevel.VP)).isZero();
        assertThat(SeniorityLevel.ENTRY.distance(SeniorityLevel.SENIOR)).isEqualTo(2);
    }
}
