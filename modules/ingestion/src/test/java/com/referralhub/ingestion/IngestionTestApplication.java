package com.referralhub.ingestion;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot entry point for this module's integration tests only.
 *
 * <p>The production entry point lives in {@code :app} and assembles all six feature modules.
 * Scanning just {@code common} and {@code ingestion} here keeps the module's tests honest about
 * what it actually depends on — if ingestion ever starts needing a bean from dedup, this fails.
 */
@SpringBootApplication(scanBasePackages = {"com.referralhub.common", "com.referralhub.ingestion"})
public class IngestionTestApplication {
}
