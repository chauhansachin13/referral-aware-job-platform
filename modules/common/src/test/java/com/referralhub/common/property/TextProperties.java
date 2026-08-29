package com.referralhub.common.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.ids.Ids;
import com.referralhub.common.text.Shingles;
import com.referralhub.common.text.TextNormalizer;
import com.referralhub.common.text.Tokens;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants the whole pipeline leans on.
 *
 * <p>Normalization runs on every posting, every query and every dedup comparison. If it can
 * throw on some input, the crawler dies on one malformed description; if it is not idempotent,
 * a re-parse produces a different hash and the change detector fires on nothing.
 */
class TextProperties {

    @Property(tries = 500)
    void normalizationNeverThrowsOnAnyInput(@ForAll String anything) {
        assertThat(TextNormalizer.canonical(anything)).isNotNull();
        assertThat(TextNormalizer.stripHtml(anything)).isNotNull();
        assertThat(Tokens.fromRaw(anything)).isNotNull();
    }

    @Property(tries = 500)
    void canonicalFormIsIdempotent(@ForAll String anything) {
        String once = TextNormalizer.canonical(anything);

        // Re-parsing stored raw payloads must produce the same hash as the first parse did.
        assertThat(TextNormalizer.canonical(once)).isEqualTo(once);
    }

    @Property(tries = 500)
    void canonicalFormIsLowercaseAndSinglySpaced(@ForAll String anything) {
        String canonical = TextNormalizer.canonical(anything);

        assertThat(canonical).isEqualTo(canonical.toLowerCase(java.util.Locale.ROOT));
        assertThat(canonical).doesNotContain("  ");
        assertThat(canonical).isEqualTo(canonical.strip());
    }

    @Provide
    Arbitrary<List<String>> tokenLists() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(8).list().ofMaxSize(30);
    }

    @Property(tries = 300)
    void jaccardIsSymmetricAndBounded(@ForAll("tokenLists") List<String> left,
                                      @ForAll("tokenLists") List<String> right) {
        Set<String> a = Shingles.of(left, 2);
        Set<String> b = Shingles.of(right, 2);

        double forward = Shingles.jaccard(a, b);
        double backward = Shingles.jaccard(b, a);

        assertThat(forward).isEqualTo(backward);
        assertThat(forward).isBetween(0.0, 1.0);
    }

    @Property(tries = 300)
    void jaccardOfASetWithItselfIsOne(@ForAll("tokenLists") List<String> tokens) {
        Set<String> shingles = Shingles.of(tokens, 2);

        assertThat(Shingles.jaccard(shingles, shingles)).isEqualTo(1.0);
    }

    @Property(tries = 300)
    void shingleCountFollowsTheWindowFormula(@ForAll("tokenLists") List<String> tokens,
                                             @ForAll @IntRange(min = 1, max = 5) int k) {
        Set<String> shingles = Shingles.of(tokens, k);

        if (tokens.isEmpty()) {
            assertThat(shingles).isEmpty();
        } else if (tokens.size() < k) {
            assertThat(shingles).hasSize(1);
        } else {
            // Distinct shingles only, so at most the number of windows.
            assertThat(shingles.size()).isLessThanOrEqualTo(tokens.size() - k + 1);
            assertThat(shingles).isNotEmpty();
        }
    }

    @Property(tries = 1000)
    void timeOrderedIdsSortByTheirTimestamp(@ForAll @net.jqwik.api.constraints.LongRange(
            min = 0, max = 4_000_000_000_000L) long earlier,
                                            @ForAll @net.jqwik.api.constraints.LongRange(
            min = 0, max = 4_000_000_000_000L) long later) {
        if (earlier >= later) {
            return;
        }
        UUID first = Ids.at(Instant.ofEpochMilli(earlier));
        UUID second = Ids.at(Instant.ofEpochMilli(later));

        assertThat(first.toString()).isLessThan(second.toString());
        assertThat(Ids.timestampOf(first)).isEqualTo(earlier);
        assertThat(first.version()).isEqualTo(7);
    }
}
