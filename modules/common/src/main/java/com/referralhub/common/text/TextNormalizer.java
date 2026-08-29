package com.referralhub.common.text;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Turns the HTML soup an ATS returns into text two postings can be compared on.
 *
 * <p>Normalization is shared by dedup and search on purpose. If the deduplicator and the
 * indexer disagreed about what "the same text" means, a job could be judged a duplicate and
 * then fail to match its own duplicate's query terms.
 */
public final class TextNormalizer {

    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern BLOCK_TAG =
            Pattern.compile("(?i)</?(p|br|div|li|ul|ol|h[1-6]|tr|td|table|section)[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(nbsp|amp|lt|gt|quot|#39|apos|rsquo|ndash|mdash);");
    private static final Pattern DIACRITIC = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9+#./ -]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextNormalizer() {
    }

    /** Strips markup but keeps sentence structure, for storage and display. */
    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /**
     * The aggressive form used for similarity: no markup, no accents, no case, no punctuation
     * beyond the characters that carry meaning in this domain ({@code c++}, {@code c#},
     * {@code node.js}, {@code sde-1}).
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = stripHtml(raw).toLowerCase(java.util.Locale.ROOT);
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        text = DIACRITIC.matcher(text).replaceAll("");
        text = NON_ALNUM.matcher(text).replaceAll(" ");
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    private static String decodeEntities(String text) {
        return ENTITY.matcher(text).replaceAll(match -> switch (match.group(1)) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "#39", "apos", "rsquo" -> "'";
            case "ndash", "mdash" -> "-";
            default -> " ";
        });
    }
}
