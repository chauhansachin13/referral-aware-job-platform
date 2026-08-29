package com.referralhub.ingestion.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.referralhub.ingestion.board.CompanyBoard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Adapters are pinned against responses recorded from the real public endpoints.
 *
 * <p>Recorded fixtures rather than live calls: the tests must fail when we break the parser, not
 * when a company edits a job title. The fixtures include a malformed entry on purpose, because
 * the behaviour that matters is what happens to the other 200 postings when one is broken.
 */
class AdapterParsingTest {

    private static CompanyBoard board(String source) {
        return new CompanyBoard(UUID.randomUUID(), UUID.randomUUID(), "Acme", source, "acme",
                true, null, null, null, Duration.ofHours(1), null, null, 0, 1.0);
    }

    private static String fixture(String name) {
        try (InputStream in = AdapterParsingTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    class Greenhouse {

        private final GreenhouseAdapter adapter = new GreenhouseAdapter();

        @Test
        @DisplayName("builds the documented public board URL")
        void buildsBoardUri() {
            assertThat(adapter.boardUri(board("greenhouse")).toString())
                    .isEqualTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true");
        }

        @Test
        @DisplayName("parses postings and skips the entry with no title")
        void parsesFixture() {
            List<ParsedPosting> postings = adapter.parse(fixture("greenhouse-jobs.json"), board("greenhouse"));

            assertThat(postings).hasSize(2);
            ParsedPosting first = postings.get(0);
            assertThat(first.externalId()).isEqualTo("4012345");
            assertThat(first.title()).isEqualTo("Senior Software Engineer, Payments");
            assertThat(first.location()).isEqualTo("San Francisco, CA");
            assertThat(first.department()).isEqualTo("Engineering");
            assertThat(first.remote()).isFalse();
            assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-08-20T14:02:11Z"));
            assertThat(first.applyUrl()).contains("boards.greenhouse.io");
            assertThat(first.rawJson()).contains("4012345");
        }

        @Test
        @DisplayName("infers remote from the location string when there is no explicit flag")
        void infersRemote() {
            List<ParsedPosting> postings = adapter.parse(fixture("greenhouse-jobs.json"), board("greenhouse"));
            assertThat(postings.get(1).remote()).isTrue();
        }

        @Test
        @DisplayName("a response that is not a job board is a parse failure, not an empty board")
        void rejectsWrongShape() {
            assertThatThrownBy(() -> adapter.parse("{\"error\":\"not found\"}", board("greenhouse")))
                    .isInstanceOf(AdapterParseException.class);
            assertThatThrownBy(() -> adapter.parse("<html>404</html>", board("greenhouse")))
                    .isInstanceOf(AdapterParseException.class);
        }
    }

    @Nested
    class Lever {

        private final LeverAdapter adapter = new LeverAdapter();

        @Test
        @DisplayName("reads location and team out of the categories object")
        void parsesFixture() {
            List<ParsedPosting> postings = adapter.parse(fixture("lever-postings.json"), board("lever"));

            assertThat(postings).hasSize(2);
            ParsedPosting first = postings.get(0);
            assertThat(first.title()).isEqualTo("Backend Engineer, Search");
            assertThat(first.location()).isEqualTo("Bengaluru, India");
            assertThat(first.department()).isEqualTo("Search");
            assertThat(first.remote()).isFalse();
        }

        @Test
        @DisplayName("trusts workplaceType=remote over the location string")
        void usesWorkplaceType() {
            List<ParsedPosting> postings = adapter.parse(fixture("lever-postings.json"), board("lever"));
            assertThat(postings.get(1).remote()).isTrue();
        }

        @Test
        @DisplayName("converts Lever's epoch-millis createdAt into an Instant")
        void parsesEpochMillis() {
            List<ParsedPosting> postings = adapter.parse(fixture("lever-postings.json"), board("lever"));
            assertThat(postings.get(0).postedAt()).isEqualTo(Instant.ofEpochMilli(1755691331000L));
        }

        @Test
        @DisplayName("an object where an array was promised is a parse failure")
        void rejectsWrongShape() {
            assertThatThrownBy(() -> adapter.parse("{\"jobs\":[]}", board("lever")))
                    .isInstanceOf(AdapterParseException.class);
        }
    }

    @Nested
    class Ashby {

        private final AshbyAdapter adapter = new AshbyAdapter();

        @Test
        @DisplayName("trusts the explicit isRemote boolean in both directions")
        void usesExplicitRemoteFlag() {
            List<ParsedPosting> postings = adapter.parse(fixture("ashby-jobs.json"), board("ashby"));

            assertThat(postings).hasSize(2);
            assertThat(postings.get(0).remote()).isFalse();
            assertThat(postings.get(1).remote()).isTrue();
        }

        @Test
        @DisplayName("keeps the unusual titles that the dedup normalizer will later canonicalize")
        void keepsTitlesVerbatim() {
            List<ParsedPosting> postings = adapter.parse(fixture("ashby-jobs.json"), board("ashby"));
            assertThat(postings.get(0).title()).isEqualTo("Member of Technical Staff, Platform");
        }

        @Test
        @DisplayName("parses the millisecond-precision timestamp Ashby returns")
        void parsesTimestamp() {
            List<ParsedPosting> postings = adapter.parse(fixture("ashby-jobs.json"), board("ashby"));
            assertThat(postings.get(1).postedAt()).isEqualTo(Instant.parse("2026-08-22T08:15:00Z"));
        }

        @Test
        @DisplayName("a missing jobs array is a parse failure")
        void rejectsWrongShape() {
            assertThatThrownBy(() -> adapter.parse("[]", board("ashby")))
                    .isInstanceOf(AdapterParseException.class);
        }
    }

    @Test
    @DisplayName("the registry resolves every supported source and rejects the rest")
    void registryResolvesAdapters() {
        AdapterRegistry registry = new AdapterRegistry(
                List.of(new GreenhouseAdapter(), new LeverAdapter(), new AshbyAdapter()));

        assertThat(registry.knownSources()).containsExactlyInAnyOrder("greenhouse", "lever", "ashby");
        assertThat(registry.require("lever")).isInstanceOf(LeverAdapter.class);
        assertThatThrownBy(() -> registry.require("workday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workday");
    }
}
