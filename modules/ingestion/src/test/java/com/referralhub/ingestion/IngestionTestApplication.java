package com.referralhub.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Boot entry point for this module's integration tests only.
 *
 * <p>The production entry point lives in {@code :app} and assembles all six feature modules.
 * Scanning just {@code common} and {@code ingestion} here keeps the module's tests honest about
 * what it actually depends on — if ingestion ever starts needing a bean from dedup, this fails.
 */
@SpringBootApplication(scanBasePackages = {"com.referralhub.common", "com.referralhub.ingestion"})
public class IngestionTestApplication {

    /**
     * A registry, because nothing else provides one here.
     *
     * <p>Micrometer's registry is auto-configured by {@code spring-boot-starter-actuator}, which
     * only the {@code app} module depends on. A feature module that instruments itself must
     * therefore bring its own registry in tests, or every {@code MeterRegistry} injection point
     * fails at context load — which is exactly how this was found.
     */
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
