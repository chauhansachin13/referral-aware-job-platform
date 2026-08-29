package com.referralhub.ingestion.fetch;

import com.referralhub.ingestion.config.IngestionProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * An HTTP GET that sends the validators we were given last time.
 *
 * <p>A board that has not changed answers {@code 304 Not Modified} with no body. For the large
 * boards that dominate the crawl budget this turns a multi-megabyte download and a full parse
 * into a few hundred bytes of headers, which is what makes crawling every company every few
 * minutes affordable at all.
 *
 * <p>{@code exchange} is used instead of {@code retrieve} because the default status handler
 * would surface a 304 as an ordinary empty response, and we need to distinguish "unchanged"
 * from "the board is now empty" — those two lead to opposite decisions downstream.
 */
@Component
public class ConditionalFetcher {

    private static final Logger log = LoggerFactory.getLogger(ConditionalFetcher.class);

    /** Used to write If-Modified-Since; always emits the correct day-of-week. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /**
     * Used to read Last-Modified. The day-of-week is deliberately not parsed: it is redundant
     * with the date, and enough real servers emit a day name that disagrees with their own
     * timestamp that validating it would silently throw away usable validators — which costs a
     * full re-download on the next crawl.
     */
    private static final DateTimeFormatter HTTP_DATE_LENIENT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final RestClient restClient;

    public ConditionalFetcher(RestClient.Builder builder, IngestionProperties properties) {
        this.restClient = builder
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    public FetchResult fetch(URI uri, String etag, Instant lastModified) {
        long startedAt = System.nanoTime();
        try {
            return restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        if (etag != null && !etag.isBlank()) {
                            headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                        }
                        if (lastModified != null) {
                            headers.set(HttpHeaders.IF_MODIFIED_SINCE,
                                    HTTP_DATE.format(ZonedDateTime.ofInstant(lastModified, ZoneOffset.UTC)));
                        }
                    })
                    .exchange((request, response) -> {
                        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                        int status = response.getStatusCode().value();

                        if (status == 304) {
                            return (FetchResult) new FetchResult.NotModified(elapsed);
                        }
                        if (status >= 400) {
                            return new FetchResult.Failed(status,
                                    "board returned HTTP " + status, elapsed);
                        }
                        String body = readBody(response);
                        HttpHeaders headers = response.getHeaders();
                        return new FetchResult.Fetched(
                                body,
                                headers.getFirst(HttpHeaders.ETAG),
                                parseLastModified(headers.getFirst(HttpHeaders.LAST_MODIFIED)),
                                elapsed);
                    }, false);
        } catch (Exception e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            log.debug("Fetch of {} failed", uri, e);
            return new FetchResult.Failed(0, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
        }
    }

    private static String readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        try (var stream = response.getBody()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Instant parseLastModified(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        int afterWeekday = value.indexOf(", ");
        if (afterWeekday >= 0) {
            value = value.substring(afterWeekday + 2);
        }
        try {
            return LocalDateTime.parse(value, HTTP_DATE_LENIENT).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
