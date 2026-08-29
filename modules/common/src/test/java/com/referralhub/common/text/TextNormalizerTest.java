package com.referralhub.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    @DisplayName("script and style bodies are removed, not just their tags")
    void dropsScriptAndStyleBodies() {
        String html = "<div>Real text<script>var x = 'tracking';</script>"
                + "<style>.a{color:red}</style> more text</div>";
        assertThat(TextNormalizer.stripHtml(html))
                .contains("Real text")
                .contains("more text")
                .doesNotContain("tracking")
                .doesNotContain("color:red");
    }

    @Test
    @DisplayName("block tags become line breaks so sentences do not run together")
    void blockTagsSeparateSentences() {
        String html = "<p>Build systems.</p><p>Ship them.</p>";
        assertThat(TextNormalizer.stripHtml(html)).isEqualTo("Build systems. Ship them.");
    }

    @Test
    @DisplayName("entities are decoded")
    void decodesEntities() {
        assertThat(TextNormalizer.stripHtml("R&amp;D&nbsp;team")).isEqualTo("R&D team");
    }

    @Test
    @DisplayName("canonical form keeps the punctuation that carries meaning in this domain")
    void keepsDomainPunctuation() {
        String canonical = TextNormalizer.canonical("Senior C++ / C# & Node.js Engineer (SDE-2)!");
        assertThat(canonical).isEqualTo("senior c++ / c# node.js engineer sde-2");
    }

    @Test
    @DisplayName("accents are folded so 'São Paulo' matches 'Sao Paulo'")
    void foldsDiacritics() {
        assertThat(TextNormalizer.canonical("São Paulo")).isEqualTo("sao paulo");
        assertThat(TextNormalizer.canonical("Zürich")).isEqualTo("zurich");
    }

    @Test
    @DisplayName("null and empty inputs are not special cases for callers")
    void handlesEmptyInput() {
        assertThat(TextNormalizer.canonical(null)).isEmpty();
        assertThat(TextNormalizer.stripHtml("")).isEmpty();
    }
}
