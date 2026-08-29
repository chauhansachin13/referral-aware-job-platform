package com.referralhub.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tokenization with a job-posting stopword list.
 *
 * <p>The list is deliberately domain-specific rather than a generic English one. Words like
 * "responsibilities", "candidate" and "opportunity" appear in essentially every posting, so
 * they contribute nothing to a similarity score while inflating every pairwise overlap.
 */
public final class Tokens {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "has", "have",
            "in", "is", "it", "its", "of", "on", "or", "our", "that", "the", "this", "to", "we",
            "will", "with", "you", "your",
            // Boilerplate that shows up in nearly every job description.
            "job", "role", "position", "opportunity", "candidate", "candidates", "applicant",
            "responsibilities", "requirements", "qualifications", "preferred", "required",
            "experience", "team", "teams", "work", "working", "company", "please", "apply",
            "equal", "employer", "opportunityemployer", "benefits", "salary", "location");

    private Tokens() {
    }

    public static List<String> of(String canonicalText) {
        if (canonicalText == null || canonicalText.isBlank()) {
            return List.of();
        }
        String[] parts = canonicalText.split(" ");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = part.strip();
            if (token.length() < 2 || STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    /** Tokens of the raw (possibly HTML) text, normalized first. */
    public static List<String> fromRaw(String raw) {
        return of(TextNormalizer.canonical(raw));
    }

    public static boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }
}
