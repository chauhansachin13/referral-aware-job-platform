package com.referralhub.common.testing;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points a Spring test context at the shared containers.
 *
 * <p>Modules call the pieces they need rather than one god-method, so a search test does not pay
 * for starting Kafka and a referral test does not pay for starting OpenSearch.
 */
public final class PlatformProperties {

    private PlatformProperties() {
    }

    public static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PlatformContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> PlatformContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> PlatformContainers.postgres().getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    public static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> PlatformContainers.kafka().getBootstrapServers());
    }

    public static void minio(DynamicPropertyRegistry registry) {
        registry.add("referralhub.storage.endpoint", () -> PlatformContainers.minio().getS3URL());
        registry.add("referralhub.storage.access-key", () -> PlatformContainers.minio().getUserName());
        registry.add("referralhub.storage.secret-key", () -> PlatformContainers.minio().getPassword());
        registry.add("referralhub.storage.region", () -> "us-east-1");
    }

    public static void openSearch(DynamicPropertyRegistry registry) {
        registry.add("referralhub.search.opensearch-uri", PlatformContainers::openSearchUri);
    }
}
