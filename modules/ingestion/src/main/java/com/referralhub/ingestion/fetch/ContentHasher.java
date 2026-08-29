package com.referralhub.ingestion.fetch;

import com.referralhub.common.text.TextNormalizer;
import com.referralhub.ingestion.adapter.ParsedPosting;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Two hashes, because "the bytes changed" and "the jobs changed" are different questions.
 *
 * <p>ATS responses churn constantly for reasons that mean nothing: a re-ordered {@code jobs}
 * array, a regenerated tracking parameter on an apply URL, a whitespace change from a CMS
 * redeploy. Emitting {@code JobIngested} on every byte-level difference would push that noise
 * through dedup, into the search indexer, and out to anyone watching a saved search.
 *
 * <p>So the raw hash is a cheap short-circuit that skips parsing entirely, and the semantic
 * hash - computed over sorted, normalized, meaning-bearing fields only - decides whether any
 * event is worth emitting.
 */
public final class ContentHasher {

    /** ASCII unit separator: cannot appear in normalized text, so fields cannot bleed together. */
    private static final char FIELD = '\u001F';

    private ContentHasher() {
    }

    /** SHA-256 of the response body exactly as received. */
    public static String raw(String body) {
        return sha256(body == null ? "" : body);
    }

    /**
     * SHA-256 over the fields a job seeker would notice, with postings sorted by external id so
     * a re-ordered response hashes identically.
     */
    public static String semantic(List<ParsedPosting> postings) {
        StringBuilder canonical = new StringBuilder(postings.size() * 128);
        postings.stream()
                .sorted(Comparator.comparing(ParsedPosting::externalId))
                .forEach(posting -> canonical.append(fingerprintOf(posting)).append(FIELD));
        return sha256(canonical.toString());
    }

    /** Per-posting hash, used to decide which individual postings changed within a board. */
    public static String posting(ParsedPosting posting) {
        return sha256(fingerprintOf(posting));
    }

    private static String fingerprintOf(ParsedPosting posting) {
        return posting.externalId() + FIELD
                + TextNormalizer.canonical(posting.title()) + FIELD
                + TextNormalizer.canonical(posting.location()) + FIELD
                + posting.remote() + FIELD
                + TextNormalizer.canonical(posting.descriptionHtml());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
