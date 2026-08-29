package com.referralhub.ingestion.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.ingestion.config.IngestionConfig;
import com.referralhub.ingestion.config.IngestionProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conditional fetching, tested over a real socket against a real HTTP server.
 *
 * <p>A mocked {@code RestClient} would prove that we call a method, which is not the claim being
 * made. The claim is that when a board answers 304 the crawler transfers no body and does no
 * work — and that is a property of the wire exchange. The JDK's own HTTP server is enough to
 * assert it, so this runs everywhere, Docker or not.
 */
class ConditionalFetcherTest {

    private static final String BODY = "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\"}]}";
    private static final String ETAG = "\"v1-abc123\"";

    private HttpServer server;
    private ConditionalFetcher fetcher;
    private URI uri;
    private final AtomicInteger bodiesServed = new AtomicInteger();
    private final List<String> seenIfNoneMatch = new CopyOnWriteArrayList<>();
    private final List<String> seenIfModifiedSince = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/board", this::handle);
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/board");

        IngestionProperties properties = new IngestionProperties();
        fetcher = new ConditionalFetcher(new IngestionConfig().ingestionRestClientBuilder(), properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        String ifModifiedSince = exchange.getRequestHeaders().getFirst("If-Modified-Since");
        if (ifNoneMatch != null) {
            seenIfNoneMatch.add(ifNoneMatch);
        }
        if (ifModifiedSince != null) {
            seenIfModifiedSince.add(ifModifiedSince);
        }

        if (ETAG.equals(ifNoneMatch)) {
            exchange.getResponseHeaders().set("ETag", ETAG);
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
        bodiesServed.incrementAndGet();
        exchange.getResponseHeaders().set("ETag", ETAG);
        exchange.getResponseHeaders().set("Last-Modified", "Wed, 20 Aug 2026 14:02:11 GMT");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("a first fetch returns the body and captures both validators")
    void firstFetchCapturesValidators() {
        FetchResult result = fetcher.fetch(uri, null, null);

        assertThat(result).isInstanceOf(FetchResult.Fetched.class);
        FetchResult.Fetched fetched = (FetchResult.Fetched) result;
        assertThat(fetched.body()).isEqualTo(BODY);
        assertThat(fetched.etag()).isEqualTo(ETAG);
        assertThat(fetched.lastModified()).isEqualTo(Instant.parse("2026-08-20T14:02:11Z"));
        assertThat(bodiesServed).hasValue(1);
    }

    @Test
    @DisplayName("re-fetching with the stored ETag yields 304 and transfers no body")
    void secondFetchIsNotModified() {
        FetchResult.Fetched first = (FetchResult.Fetched) fetcher.fetch(uri, null, null);

        FetchResult second = fetcher.fetch(uri, first.etag(), first.lastModified());

        assertThat(second).isInstanceOf(FetchResult.NotModified.class);
        assertThat(seenIfNoneMatch).containsExactly(ETAG);
        assertThat(bodiesServed).as("the 304 path must not transfer a body").hasValue(1);
    }

    @Test
    @DisplayName("a Last-Modified whose day name disagrees with its date is still usable")
    void toleratesWrongWeekdayInLastModified() {
        // The stub server answers with "Wed, 20 Aug 2026", but that date is a Thursday.
        // Real ATS front-ends do this; discarding the validator would cost a full re-download.
        FetchResult.Fetched fetched = (FetchResult.Fetched) fetcher.fetch(uri, null, null);

        assertThat(fetched.lastModified()).isEqualTo(Instant.parse("2026-08-20T14:02:11Z"));
    }

    @Test
    @DisplayName("If-Modified-Since is sent as an RFC 1123 HTTP date with the correct day name")
    void sendsHttpDateFormat() {
        fetcher.fetch(uri, null, Instant.parse("2026-08-20T14:02:11Z"));

        assertThat(seenIfModifiedSince).containsExactly("Thu, 20 Aug 2026 14:02:11 GMT");
    }

    @Test
    @DisplayName("a stale ETag gets a fresh body rather than a 304")
    void staleEtagRefetches() {
        FetchResult result = fetcher.fetch(uri, "\"v0-outdated\"", null);

        assertThat(result).isInstanceOf(FetchResult.Fetched.class);
        assertThat(bodiesServed).hasValue(1);
    }

    @Test
    @DisplayName("a 404 is reported as a failure, never as an empty board")
    void notFoundIsAFailure() {
        URI missing = URI.create(uri.toString().replace("/board", "/nope"));

        FetchResult result = fetcher.fetch(missing, null, null);

        assertThat(result).isInstanceOf(FetchResult.Failed.class);
        assertThat(((FetchResult.Failed) result).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a dead host is a failure result, not a thrown exception")
    void connectionRefusedIsAFailure() {
        FetchResult result = fetcher.fetch(URI.create("http://127.0.0.1:1/board"), null, null);

        assertThat(result).isInstanceOf(FetchResult.Failed.class);
        assertThat(((FetchResult.Failed) result).status()).isZero();
    }
}
