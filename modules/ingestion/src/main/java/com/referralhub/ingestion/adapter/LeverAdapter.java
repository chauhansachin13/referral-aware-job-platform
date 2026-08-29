package com.referralhub.ingestion.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import com.referralhub.ingestion.board.CompanyBoard;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lever public postings API.
 *
 * <p>{@code https://api.lever.co/v0/postings/{token}?mode=json}
 *
 * <p>Lever returns a bare JSON array rather than an object, and puts location, team and
 * commitment under {@code categories} — the shape differences that the adapter boundary exists
 * to absorb.
 */
@Component
public class LeverAdapter implements SourceAdapter {

    private static final String BASE = "https://api.lever.co/v0/postings/";

    @Override
    public String source() {
        return "lever";
    }

    @Override
    public URI boardUri(CompanyBoard board) {
        return URI.create(BASE + board.boardToken() + "?mode=json");
    }

    @Override
    public List<ParsedPosting> parse(String body, CompanyBoard board) {
        JsonNode root;
        try {
            root = Json.tree(body);
        } catch (RuntimeException e) {
            throw new AdapterParseException("Lever response was not JSON", e);
        }
        if (!root.isArray()) {
            throw new AdapterParseException("Lever response was not a JSON array");
        }

        List<ParsedPosting> postings = new ArrayList<>(root.size());
        for (JsonNode job : root) {
            String externalId = job.path("id").asText(null);
            String title = job.path("text").asText(null);
            if (externalId == null || title == null) {
                continue;
            }
            JsonNode categories = job.path("categories");
            String location = categories.path("location").asText("");
            String department = categories.path("team").asText("");
            String workplaceType = job.path("workplaceType").asText("");

            postings.add(new ParsedPosting(
                    externalId,
                    title,
                    job.path("description").asText(""),
                    location,
                    "remote".equalsIgnoreCase(workplaceType)
                            || GreenhouseAdapter.isRemote(location, title),
                    department,
                    job.path("hostedUrl").asText(""),
                    // Lever reports epoch milliseconds, not ISO-8601.
                    job.path("createdAt").isNumber()
                            ? Instant.ofEpochMilli(job.path("createdAt").asLong())
                            : null,
                    job.toString()));
        }
        return postings;
    }
}
