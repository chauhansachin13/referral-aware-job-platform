package com.referralhub.benchmarks;

import com.referralhub.ingestion.adapter.GreenhouseAdapter;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.fetch.ContentHasher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What one crawl costs once the bytes have arrived.
 *
 * <p>The network is deliberately excluded: it is dominated by the remote board and it is exactly
 * what the conditional-fetch path avoids. What matters here is the CPU cost of the work a 304
 * lets us skip, because that difference is the argument for the whole design.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IngestionBenchmark {

    @Param({"200", "800"})
    public int boardSize;

    private GreenhouseAdapter adapter;
    private CompanyBoard board;
    private String responseBody;
    private List<ParsedPosting> parsed;

    @Setup
    public void setUp() {
        adapter = new GreenhouseAdapter();
        board = new CompanyBoard(UUID.randomUUID(), UUID.randomUUID(), "Acme", "greenhouse",
                "acme", true, null, null, null, Duration.ofHours(1), null, null, 0, 1.0);

        List<SyntheticCorpus.Posting> corpus =
                SyntheticCorpus.generate(boardSize, 1, 0.0, 42L);
        responseBody = SyntheticCorpus.asGreenhouseJson(corpus);
        parsed = adapter.parse(responseBody, board);
    }

    /** The cheap path: what a 304 costs us instead of everything below. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public String rawHashOnly() {
        return ContentHasher.raw(responseBody);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public List<ParsedPosting> parseBoard() {
        return adapter.parse(responseBody, board);
    }

    /** Normalizes and hashes every posting: the real cost of deciding whether anything changed. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public String semanticHash() {
        return ContentHasher.semantic(parsed);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void fullIngestPath(Blackhole blackhole) {
        blackhole.consume(ContentHasher.raw(responseBody));
        List<ParsedPosting> postings = adapter.parse(responseBody, board);
        blackhole.consume(ContentHasher.semantic(postings));
        for (ParsedPosting posting : postings) {
            blackhole.consume(ContentHasher.posting(posting));
        }
    }
}
