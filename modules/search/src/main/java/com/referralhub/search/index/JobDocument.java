package com.referralhub.search.index;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referralhub.common.json.Json;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One canonical job as it is stored in the index. */
public record JobDocument(
        UUID canonicalJobId,
        UUID companyId,
        String companySlug,
        String companyName,
        String title,
        String description,
        String specialization,
        List<String> concepts,
        String role,
        String level,
        String location,
        boolean remote,
        Instant postedAt,
        int sourceCount,
        String modelId,
        float[] embedding) {

    public String toJson() {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("canonical_job_id", canonicalJobId.toString());
        node.put("company_id", companyId.toString());
        node.put("company_slug", companySlug);
        node.put("company_name", companyName);
        node.put("title", title);
        node.put("description", description);
        node.put("specialization", specialization);
        node.put("role", role);
        node.put("level", level);
        node.put("location", location);
        node.put("remote", remote);
        node.put("source_count", sourceCount);
        node.put("model_id", modelId);
        node.put("indexed_at", Instant.now().toString());
        if (postedAt != null) {
            node.put("posted_at", postedAt.toString());
        }

        ArrayNode conceptArray = node.putArray("concepts");
        concepts.forEach(conceptArray::add);

        ArrayNode vector = node.putArray("embedding");
        for (float value : embedding) {
            vector.add(value);
        }
        return node.toString();
    }
}
