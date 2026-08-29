package com.referralhub.benchmarks;

import com.referralhub.search.embed.ConceptHashingEmbeddingModel;
import com.referralhub.search.rank.FreshnessDecay;
import com.referralhub.search.rank.RankedId;
import com.referralhub.search.rank.ReciprocalRankFusion;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

/**
 * The application's share of search latency.
 *
 * <p>Everything measured here happens on either side of the OpenSearch round trip: embedding the
 * query before it is sent, and fusing plus decaying the two result lists after they come back.
 * The round trip itself is measured in the integration environment, not here, because a
 * single-node container's latency says nothing about a real cluster's.
 *
 * <p>The reason to measure this separately is that it is the part that scales with page size and
 * candidate depth rather than with corpus size — and therefore the part that a careless
 * {@code candidate-depth} change makes expensive.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SearchBenchmark {

    @Param({"50", "200", "500"})
    public int candidateDepth;

    private ConceptHashingEmbeddingModel model;
    private List<List<RankedId>> retrieverResults;
    private List<Double> weights;
    private List<UUID> ids;
    private List<Instant> postedAt;
    private Instant now;
    private String query;

    @Setup
    public void setUp() {
        model = new ConceptHashingEmbeddingModel();
        query = "senior backend engineer kubernetes payments remote";
        now = Instant.now();
        weights = List.of(1.0, 1.0);

        Random random = new Random(3L);
        ids = new ArrayList<>(candidateDepth * 2);
        postedAt = new ArrayList<>(candidateDepth * 2);
        for (int i = 0; i < candidateDepth * 2; i++) {
            ids.add(new UUID(1L, i));
            postedAt.add(now.minusSeconds(random.nextInt(120 * 86_400)));
        }

        List<RankedId> lexical = new ArrayList<>(candidateDepth);
        List<RankedId> vector = new ArrayList<>(candidateDepth);
        for (int i = 0; i < candidateDepth; i++) {
            lexical.add(new RankedId(ids.get(i), i + 1, 40.0 - i * 0.1));
            // Overlapping but not identical result sets, as the two retrievers really behave.
            vector.add(new RankedId(ids.get((i + candidateDepth / 2) % ids.size()), i + 1,
                    0.99 - i * 0.001));
        }
        retrieverResults = List.of(lexical, vector);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public float[] embedQuery() {
        return model.embed(query);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public List<ReciprocalRankFusion.Contribution> fuseResults() {
        return ReciprocalRankFusion.fuse(retrieverResults, weights);
    }

    /** Fusion plus decay plus the final sort: everything between the response and the page. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public int rankAndDecay() {
        List<ReciprocalRankFusion.Contribution> fused =
                ReciprocalRankFusion.fuse(retrieverResults, weights);

        List<double[]> scored = new ArrayList<>(fused.size());
        for (int i = 0; i < fused.size(); i++) {
            double decayed = FreshnessDecay.apply(fused.get(i).fusedScore(),
                    postedAt.get(i % postedAt.size()), now, Duration.ofDays(14));
            scored.add(new double[] {decayed, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        return scored.size();
    }

    /** The whole application-side path for one query. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public int fullQueryPath() {
        model.embed(query);
        return rankAndDecay();
    }
}
