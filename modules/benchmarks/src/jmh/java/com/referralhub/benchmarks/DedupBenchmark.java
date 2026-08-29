package com.referralhub.benchmarks;

import com.referralhub.dedup.config.DedupProperties;
import com.referralhub.dedup.match.DuplicateScorer;
import com.referralhub.dedup.match.JobFingerprint;
import com.referralhub.dedup.minhash.LshBanding;
import com.referralhub.dedup.minhash.MinHasher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
 * Candidate generation against a corpus of the size this system is designed for.
 *
 * <p>The comparison that matters is {@link #lshCandidateGeneration} against
 * {@link #linearScanBaseline}: the first is the design, the second is what happens without an
 * index. The baseline is run over a deliberately small slice because running it over the whole
 * 200k corpus would take long enough to distort the harness — which is itself the finding.
 *
 * <p>The in-memory band map stands in for the {@code lsh_bucket} table so the measurement is of
 * the algorithm rather than of Postgres.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class DedupBenchmark {

    @Param({"200000"})
    public int corpusSize;

    private static final int LINEAR_SCAN_SLICE = 2_000;

    private DedupProperties properties;
    private MinHasher hasher;
    private LshBanding banding;
    private DuplicateScorer scorer;

    private List<JobFingerprint> corpus;
    private Map<Long, List<Integer>> bandIndex;
    private JobFingerprint probe;
    private Random random;

    @Setup(Level.Trial)
    public void setUp() {
        properties = new DedupProperties();
        hasher = new MinHasher(properties.getNumHashes(), properties.getHashSeed());
        banding = LshBanding.of(properties.getNumHashes(), properties.getBands());
        scorer = new DuplicateScorer(properties);
        random = new Random(7L);

        List<SyntheticCorpus.Posting> postings =
                SyntheticCorpus.generate(corpusSize, 2_000, 0.15, 11L);

        corpus = new ArrayList<>(corpusSize);
        bandIndex = new HashMap<>(corpusSize * 2);

        for (int i = 0; i < postings.size(); i++) {
            SyntheticCorpus.Posting posting = postings.get(i);
            JobFingerprint fingerprint = JobFingerprint.of(posting.id(), posting.companyId(),
                    posting.title(), posting.description(), posting.location(), posting.remote(),
                    hasher, properties.getShingleSize());
            corpus.add(fingerprint);

            long[] bands = banding.bandHashes(fingerprint.signature());
            for (int band = 0; band < bands.length; band++) {
                // Band index folded in so two bands with identical rows do not collide.
                bandIndex.computeIfAbsent(bands[band] * 31 + band,
                        key -> new ArrayList<>()).add(i);
            }
        }
        probe = corpus.get(random.nextInt(corpus.size()));
    }

    /** One posting's fingerprint: normalize, tokenize, shingle, 128 permutations. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public JobFingerprint fingerprintOnePosting() {
        SyntheticCorpus.Posting posting =
                SyntheticCorpus.generate(1, 1, 0.0, random.nextLong()).get(0);
        return JobFingerprint.of(posting.id(), posting.companyId(), posting.title(),
                posting.description(), posting.location(), posting.remote(), hasher,
                properties.getShingleSize());
    }

    /** The design: bounded work regardless of corpus size. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public int lshCandidateGeneration() {
        long[] bands = banding.bandHashes(probe.signature());
        Set<Integer> candidates = new HashSet<>();
        for (int band = 0; band < bands.length; band++) {
            List<Integer> bucket = bandIndex.get(bands[band] * 31 + band);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates.size();
    }

    /** LSH retrieval plus the exact scoring pass over the shortlist. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public double retrieveAndScore() {
        long[] bands = banding.bandHashes(probe.signature());
        Set<Integer> candidates = new HashSet<>();
        for (int band = 0; band < bands.length; band++) {
            List<Integer> bucket = bandIndex.get(bands[band] * 31 + band);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }

        double best = 0;
        int scored = 0;
        for (Integer index : candidates) {
            if (scored++ >= properties.getExactScoreLimit()) {
                break;
            }
            best = Math.max(best, scorer.score(probe, corpus.get(index)).total());
        }
        return best;
    }

    /** What deduplication costs without an index. Note the slice size, and the units. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void linearScanBaseline(Blackhole blackhole) {
        double best = 0;
        for (int i = 0; i < LINEAR_SCAN_SLICE; i++) {
            best = Math.max(best, scorer.score(probe, corpus.get(i)).total());
        }
        blackhole.consume(best);
    }
}
