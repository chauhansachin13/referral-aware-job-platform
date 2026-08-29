package com.referralhub.trust.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OneTimeCodesTest {

    @Test
    @DisplayName("an issued code is six digits and its own hash verifies")
    void issuedCodesVerify() {
        OneTimeCodes.Issued issued = OneTimeCodes.issue();

        assertThat(issued.plaintext()).matches("\\d{6}");
        assertThat(OneTimeCodes.matches(issued.plaintext(), issued.hash(), issued.salt())).isTrue();
    }

    @Test
    @DisplayName("a wrong code does not verify")
    void wrongCodesFail() {
        OneTimeCodes.Issued issued = OneTimeCodes.issue();
        String wrong = issued.plaintext().equals("000000") ? "111111" : "000000";

        assertThat(OneTimeCodes.matches(wrong, issued.hash(), issued.salt())).isFalse();
        assertThat(OneTimeCodes.matches(null, issued.hash(), issued.salt())).isFalse();
    }

    @Test
    @DisplayName("the same code under a different salt hashes differently")
    void saltingDefeatsPrecomputation() {
        OneTimeCodes.Issued first = OneTimeCodes.issue();
        OneTimeCodes.Issued second = OneTimeCodes.issue();

        // A rainbow table over 10^6 codes is trivial; per-verification salt is what stops it.
        assertThat(OneTimeCodes.hash("123456", first.salt()))
                .isNotEqualTo(OneTimeCodes.hash("123456", second.salt()));
    }

    @Test
    @DisplayName("salts are unique per issue")
    void saltsAreUnique() {
        Set<String> salts = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            salts.add(OneTimeCodes.issue().salt());
        }
        assertThat(salts).hasSize(500);
    }

    @Test
    @DisplayName("codes are spread across the space rather than clustering")
    void codesAreWellDistributed() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            codes.add(OneTimeCodes.issue().plaintext());
        }
        // Birthday collisions in 10^6 are expected; near-total uniqueness is not.
        assertThat(codes.size()).isGreaterThan(1_950);
    }
}
