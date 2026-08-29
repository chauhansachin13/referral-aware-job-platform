package com.referralhub.common.testing;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One set of containers per JVM, shared by every integration test.
 *
 * <p>Containers are started lazily on first touch and never stopped — Ryuk reaps them when the
 * JVM exits. Starting Postgres and Kafka per test class turned a 40-second suite into a
 * six-minute one, and the isolation it bought was illusory anyway: each test truncates the
 * tables it owns.
 */
public final class PlatformContainers {

    private static final String POSTGRES_IMAGE = "pgvector/pgvector:pg16";
    private static final String KAFKA_IMAGE = "apache/kafka:3.9.0";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-09-13T20-26-02Z";
    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:2.17.1";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    private PlatformContainers() {
    }

    private static final class PostgresHolder {
        private static final PostgreSQLContainer<?> INSTANCE = start();

        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                    DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("referralhub")
                    .withUsername("referralhub")
                    .withPassword("referralhub")
                    .withReuse(true);
            container.start();
            return container;
        }
    }

    private static final class KafkaHolder {
        private static final KafkaContainer INSTANCE = start();

        private static KafkaContainer start() {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
                    .withReuse(true);
            container.start();
            return container;
        }
    }

    private static final class MinioHolder {
        private static final MinIOContainer INSTANCE = start();

        private static MinIOContainer start() {
            MinIOContainer container = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE))
                    .withUserName("referralhub")
                    .withPassword("referralhub-secret")
                    .withReuse(true);
            container.start();
            return container;
        }
    }

    private static final class OpenSearchHolder {
        private static final OpenSearchContainer<?> INSTANCE = start();

        private static OpenSearchContainer<?> start() {
            // The official module rather than a hand-rolled GenericContainer.
            //
            // Configuring OpenSearch by hand cost several CI cycles: DISABLE_SECURITY_PLUGIN
            // removes the plugin, which makes plugins.security.disabled an unknown setting and
            // exits the process with code 64; and a plain HTTP wait reports "the process died"
            // and "the cluster is still forming" identically. This module already encodes which
            // switches a given version needs and what to wait for.
            OpenSearchContainer<?> container =
                    new OpenSearchContainer<>(DockerImageName.parse(OPENSEARCH_IMAGE))
                            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms1g -Xmx1g")
                            .withStartupTimeout(Duration.ofMinutes(4))
                            .withReuse(true);

            // Accumulated in memory, so the log survives Testcontainers removing the failed
            // container. getLogs() on a dead container returns nothing, which is how the last
            // attempt produced an empty diagnosis.
            ToStringConsumer captured = new ToStringConsumer();
            container.withLogConsumer(captured);

            try {
                container.start();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "OpenSearch container failed to start.\n--- container log ---\n"
                                + tail(captured.toUtf8String()) + "\n--- end container log ---", e);
            }
            return container;
        }
    }

    /** Last 60 lines; the startup banner is long and the failure is always at the end. */
    private static String tail(String logs) {
        if (logs == null || logs.isBlank()) {
            return "(container produced no output)";
        }
        String[] lines = logs.split("\n");
        int from = Math.max(0, lines.length - 60);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static final class RedisHolder {
        private static final GenericContainer<?> INSTANCE = start();

        private static GenericContainer<?> start() {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort())
                    .withReuse(true);
            container.start();
            return container;
        }
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    public static PostgreSQLContainer<?> postgres() {
        return PostgresHolder.INSTANCE;
    }

    public static KafkaContainer kafka() {
        return KafkaHolder.INSTANCE;
    }

    public static MinIOContainer minio() {
        return MinioHolder.INSTANCE;
    }

    public static OpenSearchContainer<?> openSearch() {
        return OpenSearchHolder.INSTANCE;
    }

    /** Uses the module's own accessor rather than assuming the mapped port. */
    public static String openSearchUri() {
        return openSearch().getHttpHostAddress();
    }
}
