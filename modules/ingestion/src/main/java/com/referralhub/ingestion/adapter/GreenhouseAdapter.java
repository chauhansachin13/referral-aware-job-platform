package com.referralhub.ingestion.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import com.referralhub.common.text.TextNormalizer;
import com.referralhub.ingestion.board.CompanyBoard;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Greenhouse public job board API.
 *
 * <p>{@code https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true}
 *
 * <p>This is the documented public board endpoint — the same JSON that powers a company's own
 * careers page. No authentication, no scraping of the rendered HTML.
 */
@Component
public class GreenhouseAdapter implements SourceAdapter {

    private static final String BASE = "https://boards-api.greenhouse.io/v1/boards/";

    @Override
    public String source() {
        return "greenhouse";
    }

    @Override
    public URI boardUri(CompanyBoard board) {
        return URI.create(BASE + board.boardToken() + "/jobs?content=true");
    }

    @Override
    public List<ParsedPosting> parse(String body, CompanyBoard board) {
        JsonNode root;
        try {
            root = Json.tree(body);
        } catch (RuntimeException e) {
            throw new AdapterParseException("Greenhouse response was not JSON", e);
        }
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            throw new AdapterParseException("Greenhouse response has no 'jobs' array");
        }

        List<ParsedPosting> postings = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            String externalId = job.path("id").asText(null);
            String title = job.path("title").asText(null);
            if (externalId == null || title == null) {
                // A single malformed entry must not discard the other 200 postings.
                continue;
            }
            String location = job.path("location").path("name").asText("");
            String department = job.path("departments").isArray() && !job.path("departments").isEmpty()
                    ? job.path("departments").get(0).path("name").asText("")
                    : "";
            // Greenhouse returns the description as HTML-escaped markup inside a JSON string.
            String content = job.path("content").asText("");

            postings.add(new ParsedPosting(
                    externalId,
                    title,
                    content,
                    location,
                    isRemote(location, title),
                    department,
                    job.path("absolute_url").asText(""),
                    parseInstant(job.path("updated_at").asText(null)),
                    job.toString()));
        }
        return postings;
    }

    static boolean isRemote(String location, String title) {
        String haystack = TextNormalizer.canonical(location + " " + title);
        return haystack.contains("remote") || haystack.contains("anywhere")
                || haystack.contains("distributed");
    }

    static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
