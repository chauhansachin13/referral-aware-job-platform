package com.referralhub.dedup.title;

/**
 * A posting title reduced to the two things that decide whether two postings are the same role.
 *
 * @param role  canonical role family, e.g. {@code software engineer}
 * @param level position on the shared ladder
 * @param specialization the qualifier that survived normalization, e.g. {@code payments};
 *                       kept separate because "Software Engineer, Payments" and "Software
 *                       Engineer, Search" are different jobs at the same level
 */
public record CanonicalTitle(String role, SeniorityLevel level, String specialization) {

    public String key() {
        return level.name().toLowerCase(java.util.Locale.ROOT) + "|" + role;
    }
}
