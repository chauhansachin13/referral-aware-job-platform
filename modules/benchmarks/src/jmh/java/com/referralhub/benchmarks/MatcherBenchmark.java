package com.referralhub.benchmarks;

import com.referralhub.referral.match.MatchingWeights;
import com.referralhub.referral.match.ReferralMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
 * Assignment cost as the queue grows.
 *
 * <p>The matcher is O(requests x referrers) by construction, and this exists to keep that honest:
 * if a future change makes it quadratic in requests, the shape of these numbers says so
 * immediately.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MatcherBenchmark {

    @Param({"50", "400", "2000"})
    public int pendingRequests;

    @Param({"3", "25"})
    public int referrers;

    private List<ReferralMatcher.PendingRequest> requests;
    private List<ReferralMatcher.EligibleReferrer> pool;
    private MatchingWeights weights;

    @Setup
    public void setUp() {
        Random random = new Random(13L);
        weights = MatchingWeights.defaults();
        String[] departments = {"Payments", "Search", "Infrastructure", "Growth"};
        String[] levels = {"MID", "SENIOR", "STAFF", "PRINCIPAL"};
        String[] tech = {"java", "go", "kubernetes", "kafka", "postgres", "react"};

        requests = new ArrayList<>(pendingRequests);
        Instant base = Instant.now().minusSeconds(86_400);
        for (int i = 0; i < pendingRequests; i++) {
            requests.add(new ReferralMatcher.PendingRequest(
                    new UUID(1L, i), new UUID(2L, i), new UUID(3L, i % 40),
                    departments[random.nextInt(departments.length)],
                    levels[random.nextInt(levels.length)],
                    pick(random, tech),
                    base.plusSeconds(i)));
        }

        pool = new ArrayList<>(referrers);
        for (int i = 0; i < referrers; i++) {
            pool.add(new ReferralMatcher.EligibleReferrer(
                    new UUID(4L, i),
                    departments[random.nextInt(departments.length)],
                    levels[random.nextInt(levels.length)],
                    pick(random, tech),
                    random.nextDouble(),
                    1 + random.nextInt(5)));
        }
    }

    /**
     * Two distinct technologies.
     *
     * <p>Not {@code Set.of(a, b)}: that throws on duplicate elements, and drawing twice from a
     * six-element array collides constantly. The first version of this benchmark did exactly
     * that and failed in setup, which is why it produced no results at all rather than bad ones.
     */
    private static Set<String> pick(Random random, String[] values) {
        Set<String> chosen = new HashSet<>(2);
        while (chosen.size() < 2) {
            chosen.add(values[random.nextInt(values.length)]);
        }
        return chosen;
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public List<ReferralMatcher.Assignment> assignAll() {
        return ReferralMatcher.assign(requests, pool, weights);
    }
}
