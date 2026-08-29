package com.referralhub.search.index;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.text.TextNormalizer;
import com.referralhub.dedup.canonical.CanonicalJob;
import com.referralhub.dedup.canonical.CanonicalJobStore;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyRecord;
import com.referralhub.search.config.SearchProperties;
import com.referralhub.search.embed.ConceptHashingEmbeddingModel;
import com.referralhub.search.embed.EmbeddingModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Projects canonical jobs into the search index. */
@Service
public class JobIndexer {

    private static final Logger log = LoggerFactory.getLogger(JobIndexer.class);

    private final OpenSearchGateway gateway;
    private final CanonicalJobStore canonicalJobs;
    private final BoardStore companies;
    private final EmbeddingModel embeddingModel;
    private final SearchProperties properties;
    private final Counter indexedCounter;

    public JobIndexer(OpenSearchGateway gateway,
                      CanonicalJobStore canonicalJobs,
                      BoardStore companies,
                      EmbeddingModel embeddingModel,
                      SearchProperties properties,
                      MeterRegistry meters) {
        this.gateway = gateway;
        this.canonicalJobs = canonicalJobs;
        this.companies = companies;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.indexedCounter = meters.counter("referralhub.search.indexed");
    }

    /**
     * Creates the index on startup if it does not exist.
     *
     * <p>Failure here is logged, not fatal: the API should still start and serve everything that
     * does not depend on OpenSearch rather than crash-looping the whole application because one
     * downstream is slow to come up.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexExists() {
        if (!properties.isIndexerEnabled()) {
            return;
        }
        try {
            gateway.ensureIndex(embeddingModel.dimensions());
        } catch (Exception e) {
            log.warn("Could not ensure search index on startup: {}", e.toString());
        }
    }

    public void index(UUID canonicalJobId) {
        CanonicalJob job = canonicalJobs.findById(canonicalJobId)
                .orElseThrow(() -> new NotFoundException("Canonical job", canonicalJobId));
        CompanyRecord company = companies.findCompany(job.companyId())
                .orElseThrow(() -> new NotFoundException("Company", job.companyId()));

        gateway.indexDocument(job.id().toString(), toDocument(job, company).toJson());
        indexedCounter.increment();
    }

    JobDocument toDocument(CanonicalJob job, CompanyRecord company) {
        String plainDescription = TextNormalizer.stripHtml(job.descriptionHtml());

        // The embedded text is deliberately not the description alone: title and specialization
        // carry most of the discriminating signal, and a 6 KB description would otherwise dilute
        // them to nothing in a single averaged vector.
        String embeddableText = job.title() + " " + job.specialization() + " "
                + job.canonicalRole() + " " + job.location() + " " + plainDescription;

        float[] embedding = embeddingModel.embed(embeddableText);
        if (embedding.length != embeddingModel.dimensions()) {
            throw new IllegalStateException("Embedding model returned " + embedding.length
                    + " dimensions but declares " + embeddingModel.dimensions());
        }

        List<String> concepts = embeddingModel instanceof ConceptHashingEmbeddingModel model
                ? model.conceptsOf(embeddableText)
                : List.of();

        return new JobDocument(
                job.id(),
                job.companyId(),
                company.slug(),
                company.name(),
                job.title(),
                plainDescription,
                job.specialization(),
                concepts,
                job.canonicalRole(),
                job.canonicalLevel(),
                job.location(),
                job.remote(),
                job.firstSeenAt(),
                job.sourceCount(),
                embeddingModel.modelId(),
                embedding);
    }

    public void remove(UUID canonicalJobId) {
        gateway.deleteDocument(canonicalJobId.toString());
    }
}
