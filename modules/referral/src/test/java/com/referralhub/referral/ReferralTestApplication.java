package com.referralhub.referral;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Boot entry point for this module's integration tests.
 *
 * <p>The scan list is deliberately narrow and each entry earns its place:
 * <ul>
 *   <li>{@code ingestion.board} — the company registry, which trust reads to verify a work
 *       email domain. Not the crawler.</li>
 *   <li>{@code dedup.canonical} — the canonical job store the matcher reads. Emphatically not
 *       all of {@code dedup}: that pulls in {@code DedupService}, which needs the raw posting
 *       store and a MinHasher that nothing in a referral test uses.</li>
 * </ul>
 *
 * <p>Scanning whole modules "just in case" is what hid the original failure — the context
 * dragged in a service whose own dependencies were not on the scan list.
 */
@SpringBootApplication(scanBasePackages = {
        "com.referralhub.common",
        "com.referralhub.ingestion.board",
        "com.referralhub.dedup.canonical",
        "com.referralhub.trust",
        "com.referralhub.referral"})
public class ReferralTestApplication {

    /** See {@code IngestionTestApplication}: actuator is not on this module's test classpath. */
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
