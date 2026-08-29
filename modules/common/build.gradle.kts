plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.validation)
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    api(libs.spring.kafka)
    api("io.micrometer:micrometer-core")
    implementation(libs.caffeine)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.core)
    testFixturesApi(libs.testcontainers.junit)
    testFixturesApi(libs.testcontainers.postgres)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.testcontainers.minio)
    // Purpose-built: it knows which OpenSearch versions need the security plugin disabled
    // and which startup signal to wait for, both of which cost several CI cycles by hand.
    testFixturesApi(libs.testcontainers.opensearch)
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.flyway.core)
    testFixturesApi(libs.flyway.postgresql)
    testFixturesApi(libs.postgresql)
}
