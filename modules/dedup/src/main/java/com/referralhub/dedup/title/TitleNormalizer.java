package com.referralhub.dedup.title;

import com.referralhub.common.text.TextNormalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the industry's mutually incompatible title vocabularies onto one ladder.
 *
 * <p>This is the piece that makes cross-company deduplication possible at all. The same job is
 * advertised as "SDE-1", "Software Engineer I", "Member of Technical Staff", "Software
 * Development Engineer 1" and "Engineer, Level 3" depending on who is hiring, and the
 * description text they share is boilerplate that would match half the corpus. Without title
 * canonicalization the deduplicator is choosing between missing every real repost and merging
 * every posting a company has.
 *
 * <p>Rule ordering is significant and deliberate: the longest, most specific patterns are tried
 * first, so "senior member of technical staff" cannot be shadowed by the "member of technical
 * staff" rule, and "engineering manager" cannot be swallowed by "engineer".
 */
public final class TitleNormalizer {

    /**
     * Bracketed tails are noise: "(Req #40122)", "[Remote]", "(Contract)".
     *
     * <p>Deliberately narrower than it first was. Truncating at the first "-" or "|" also threw
     * away the specialization - "Sr. Software Engineer - Payments" became "sr software engineer",
     * and "Engineering | Backend Engineer" became "engineering", which matches no role at all.
     * Separators are normalized to spaces instead, and the specialization extractor removes the
     * level and role words, so nothing meaningful is discarded by position.
     */
    private static final Pattern BRACKETED_TAIL = Pattern.compile("[(\\[{][^)\\]}]*[)\\]}]");

    private static final Pattern SEPARATORS = Pattern.compile("[|/]|\\s-\\s");

    private static final Pattern REQ_NUMBER =
            Pattern.compile("\\b(req|jr|job)?\\s*#\\s*\\d+\\b|\\breq\\s+\\d+\\b");

    /** Level rules, most specific first. */
    private static final Map<Pattern, SeniorityLevel> LEVEL_RULES = new LinkedHashMap<>();

    /** Role rules, most specific first. */
    private static final Map<Pattern, String> ROLE_RULES = new LinkedHashMap<>();

    static {
        // --- Level -----------------------------------------------------------------------
        put(LEVEL_RULES, "\\b(vp|vice president|svp|evp)\\b", SeniorityLevel.VP);
        put(LEVEL_RULES, "\\b(director|head of)\\b", SeniorityLevel.DIRECTOR);
        put(LEVEL_RULES, "\\b(engineering manager|em|people manager|manager)\\b", SeniorityLevel.MANAGER);
        put(LEVEL_RULES, "\\b(distinguished|fellow|principal|architect)\\b", SeniorityLevel.PRINCIPAL);
        put(LEVEL_RULES, "\\bsenior (member of technical staff|mts)\\b", SeniorityLevel.SENIOR);
        put(LEVEL_RULES, "\\bsmts\\b", SeniorityLevel.SENIOR);
        // Must precede the bare "staff" rule: that rule matches the word inside "member of
        // technical staff" and would classify a mid-level MTS posting as staff-level.
        put(LEVEL_RULES, "\\b(member of technical staff|mts)\\b", SeniorityLevel.MID);
        put(LEVEL_RULES, "\\b(staff|lead|tech lead|technical lead)\\b", SeniorityLevel.STAFF);
        put(LEVEL_RULES, "\\b(senior|sr|snr)\\.?\\b", SeniorityLevel.SENIOR);
        put(LEVEL_RULES, "\\b(intern|internship|co-?op|trainee)\\b", SeniorityLevel.INTERN);
        put(LEVEL_RULES, "\\b(junior|jr|associate|graduate|new grad|entry level|campus)\\b",
                SeniorityLevel.ENTRY);
        // Numeric ladders. Amazon-style SDE-1 is entry, SDE-2 mid, SDE-3 senior; Roman numerals
        // and "level N" follow the same convention across the companies that use them.
        put(LEVEL_RULES, "\\b(sde|swe|se|engineer|developer|analyst|scientist|designer)[\\s-]*(iii|3)\\b",
                SeniorityLevel.SENIOR);
        put(LEVEL_RULES, "\\b(sde|swe|se|engineer|developer|analyst|scientist|designer)[\\s-]*(ii|2)\\b",
                SeniorityLevel.MID);
        put(LEVEL_RULES, "\\b(sde|swe|se|engineer|developer|analyst|scientist|designer)[\\s-]*(i|1)\\b",
                SeniorityLevel.ENTRY);
        put(LEVEL_RULES, "\\blevel[\\s-]*3\\b", SeniorityLevel.SENIOR);
        put(LEVEL_RULES, "\\blevel[\\s-]*2\\b", SeniorityLevel.MID);
        put(LEVEL_RULES, "\\blevel[\\s-]*1\\b", SeniorityLevel.ENTRY);

        // --- Role ------------------------------------------------------------------------
        put(ROLE_RULES, "\\b(site reliability|sre|devops|dev ops|platform engineer|"
                + "infrastructure engineer|production engineer)\\b", "site reliability engineer");
        put(ROLE_RULES, "\\b(machine learning|ml|deep learning|ai) engineer\\b|\\bmle\\b",
                "machine learning engineer");
        put(ROLE_RULES, "\\b(research scientist|research engineer)\\b", "research scientist");
        put(ROLE_RULES, "\\bdata scientist\\b", "data scientist");
        put(ROLE_RULES, "\\bdata engineer\\b", "data engineer");
        put(ROLE_RULES, "\\bdata analyst\\b|\\bbusiness analyst\\b", "data analyst");
        put(ROLE_RULES, "\\b(security engineer|application security|appsec|infosec)\\b",
                "security engineer");
        put(ROLE_RULES, "\\b(sdet|qa engineer|test engineer|quality engineer)\\b", "qa engineer");
        put(ROLE_RULES, "\\b(android|ios|mobile) (engineer|developer)\\b", "mobile engineer");
        put(ROLE_RULES, "\\b(frontend|front end|front-end|ui) (engineer|developer)\\b",
                "frontend engineer");
        put(ROLE_RULES, "\\b(backend|back end|back-end|server side) (engineer|developer)\\b",
                "backend engineer");
        put(ROLE_RULES, "\\b(full stack|fullstack|full-stack) (engineer|developer)\\b",
                "full stack engineer");
        put(ROLE_RULES, "\\b(technical program manager|tpm)\\b", "technical program manager");
        put(ROLE_RULES, "\\b(product manager|pm|product owner)\\b", "product manager");
        put(ROLE_RULES, "\\b(product designer|ux designer|ui designer|interaction designer)\\b",
                "product designer");
        put(ROLE_RULES, "\\b(solutions architect|solution architect|sales engineer)\\b",
                "solutions architect");
        // The catch-all. Everything above is a specialization of this.
        // "tech lead" and friends live here because in an engineering org they name the same
        // job family as "staff engineer" — the rung is carried by the level rules, not the noun.
        put(ROLE_RULES, "\\b(software development engineer|software engineer|software developer|"
                + "sde|swe|member of technical staff|mts|smts|programmer|developer|engineer|"
                + "tech lead|technical lead|team lead|engineering lead)\\b",
                "software engineer");
    }

    private static <T> void put(Map<Pattern, T> rules, String regex, T value) {
        rules.put(Pattern.compile(regex), value);
    }

    private TitleNormalizer() {
    }

    public static CanonicalTitle normalize(String rawTitle) {
        String text = TextNormalizer.canonical(rawTitle == null ? "" : rawTitle);
        text = BRACKETED_TAIL.matcher(text).replaceAll(" ");
        text = REQ_NUMBER.matcher(text).replaceAll(" ");
        text = SEPARATORS.matcher(text).replaceAll(" ");
        text = text.replaceAll("\\s+", " ").trim();

        return new CanonicalTitle(detectRole(text), detectLevel(text), extractSpecialization(text));
    }

    private static SeniorityLevel detectLevel(String text) {
        for (Map.Entry<Pattern, SeniorityLevel> rule : LEVEL_RULES.entrySet()) {
            if (rule.getKey().matcher(text).find()) {
                return rule.getValue();
            }
        }
        return SeniorityLevel.UNSPECIFIED;
    }

    private static String detectRole(String text) {
        for (Map.Entry<Pattern, String> rule : ROLE_RULES.entrySet()) {
            if (rule.getKey().matcher(text).find()) {
                return rule.getValue();
            }
        }
        // Unknown vocabulary: keep the words themselves so at least exact repeats still match.
        return text.isBlank() ? "unknown" : text;
    }

    /**
     * What is left once the level and role words are removed — the qualifier that distinguishes
     * two postings at the same level in the same family.
     */
    private static String extractSpecialization(String full) {
        String remainder = full;

        // Roles first. The numeric ladder rules consume the role noun ("engineer ii"), and if
        // they run first they leave "software" stranded, which then looks like a specialization
        // and makes "Software Engineer II" differ from "SDE-2".
        for (Pattern pattern : ROLE_RULES.keySet()) {
            remainder = pattern.matcher(remainder).replaceAll(" ");
        }
        for (Pattern pattern : LEVEL_RULES.keySet()) {
            remainder = pattern.matcher(remainder).replaceAll(" ");
        }

        // Debris the two passes leave behind: orphaned ladder numerals, the generic nouns that
        // appear in every title, and stray punctuation from "Sr. Software Engineer".
        remainder = remainder.replaceAll("\\b(i|ii|iii|iv|v|1|2|3|4|5|l[1-9]|level)\\b", " ");
        remainder = remainder.replaceAll("\\b(and|the|of|for|to|in|at|with|a|an|team|group|org|"
                + "tech|technical|engineering|software|engineer|developer|member)\\b", " ");
        remainder = remainder.replaceAll("[^a-z0-9+# ]", " ").replaceAll("\\s+", " ").trim();
        return remainder;
    }

    /** Titles normalizing to the same role and level. */
    public static boolean sameRoleAndLevel(String left, String right) {
        return normalize(left).key().equals(normalize(right).key());
    }

    /** Convenience for callers that only want the words, e.g. the search analyzer. */
    public static List<String> knownRoles() {
        return ROLE_RULES.values().stream().distinct().toList();
    }
}
