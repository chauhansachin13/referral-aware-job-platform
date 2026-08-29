package com.referralhub.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    @DisplayName("job-posting boilerplate is dropped, real signal is kept")
    void dropsBoilerplate() {
        assertThat(Tokens.fromRaw("<p>We are looking for a candidate with experience in Kubernetes</p>"))
                .containsExactly("looking", "kubernetes");
    }

    @Test
    @DisplayName("single characters are dropped but two-character tech terms survive")
    void keepsShortTechTerms() {
        assertThat(Tokens.fromRaw("Go and R and AI")).containsExactly("go", "ai");
    }

    @Test
    @DisplayName("stopword membership is queryable for callers that build their own pipelines")
    void exposesStopwordCheck() {
        assertThat(Tokens.isStopword("responsibilities")).isTrue();
        assertThat(Tokens.isStopword("kafka")).isFalse();
    }
}
