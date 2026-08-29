package com.referralhub.ingestion.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.referralhub.common.json.Json;
import com.referralhub.ingestion.board.CompanyBoard;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Ashby public job board API.
 *
 * <p>{@code https://api.ashbyhq.com/posting-api/job-board/{token}}
 *
 * <p>Ashby is the only one of the three that states remoteness as a boolean rather than leaving
 * it to be inferred from the location string, so this adapter trusts the field and falls back to
 * the heuristic only when it is absent.
 */
@Component
public class AshbyAdapter implements SourceAdapter {

    private static final String BASE = "https://api.ashbyhq.com/posting-api/job-board/";

    @Override
    public String source() {
        return "ashby";
    }

    @Override
    public URI boardUri(CompanyBoard board) {
        return URI.create(BASE + board.boardToken() + "?includeCompensation=true");
    }

    @Override
    public List<ParsedPosting> parse(String body, CompanyBoard board) {
        JsonNode root;
        try {
            root = Json.tree(body);
        } catch (RuntimeException e) {
            throw new AdapterParseException("Ashby response was not JSON", e);
        }
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            throw new AdapterParseException("Ashby response has no 'jobs' array");
        }

        List<ParsedPosting> postings = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            String externalId = job.path("id").asText(null);
            String title = job.path("title").asText(null);
            if (externalId == null || title == null) {
                continue;
            }
            String location = job.path("location").asText("");
            JsonNode remoteNode = job.path("isRemote");

            postings.add(new ParsedPosting(
                    externalId,
                    title,
                    job.path("descriptionHtml").asText(""),
                    location,
                    remoteNode.isBoolean()
                            ? remoteNode.asBoolean()
                            : GreenhouseAdapter.isRemote(location, title),
                    job.path("department").asText(""),
                    job.path("jobUrl").asText(""),
                    GreenhouseAdapter.parseInstant(job.path("publishedAt").asText(null)),
                    job.toString()));
        }
        return postings;
    }
}
