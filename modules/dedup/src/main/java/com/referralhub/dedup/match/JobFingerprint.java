package com.referralhub.dedup.match;

import com.referralhub.common.text.Shingles;
import com.referralhub.common.text.TextNormalizer;
import com.referralhub.common.text.Tokens;
import com.referralhub.dedup.minhash.MinHasher;
import com.referralhub.dedup.title.CanonicalTitle;
import com.referralhub.dedup.title.TitleNormalizer;
import java.util.Set;
import java.util.UUID;

/**
 * Everything needed to compare two postings, computed once.
 *
 * <p>Building this is the expensive part — normalizing a 6 KB description, shingling it and
 * hashing 128 permutations — and it happens once per posting, not once per candidate pair.
 */
public record JobFingerprint(
        UUID id,
        UUID companyId,
        String rawTitle,
        CanonicalTitle title,
        String location,
        boolean remote,
        Set<String> shingles,
        int[] signature) {

    public static JobFingerprint of(UUID id, UUID companyId, String rawTitle, String description,
                                    String location, boolean remote, MinHasher hasher,
                                    int shingleSize) {
        // The title is folded into the shingled text deliberately: two postings whose
        // descriptions are identical boilerplate but whose titles differ are different jobs, and
        // description-only shingles would score them as identical.
        String combined = rawTitle + " " + description;
        Set<String> shingles = Shingles.of(Tokens.fromRaw(combined), shingleSize);
        return new JobFingerprint(
                id,
                companyId,
                rawTitle,
                TitleNormalizer.normalize(rawTitle),
                TextNormalizer.canonical(location),
                remote,
                shingles,
                hasher.signature(shingles));
    }
}
