package com.referralhub.common.testing;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
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
        private static final GenericContainer<?> INSTANCE = start();

        private static GenericContainer<?> start() {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(OPENSEARCH_IMAGE))
                    .withExposedPorts(9200)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                    // Belt and braces: 2.12+ refuses to start without an admin password unless
                    // security is off, and the two switches are honoured by different versions.
                    .withEnv("plugins.security.disabled", "true")
                    // 512m is enough on a developer machine and not enough on a shared CI runner,
                    // where the JVM starts slowly and the cluster never reaches green in time.
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms1g -Xmx1g")
                    .withEnv("bootstrap.memory_lock", "false")
                    .waitingFor(Wait.forHttp("/_cluster/health")
                            .forPort(9200)
                            .forStatusCodeMatching(code -> code == 200 || code == 401)
                            .withStartupTimeout(Duration.ofMinutes(4)))
                    .withStartupTimeout(Duration.ofMinutes(4))
                    .withReuse(true);
            container.start();
            return container;
        }
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

    public static GenericContainer<?> openSearch() {
        return OpenSearchHolder.INSTANCE;
    }

    public static String openSearchUri() {
        GenericContainer<?> os = openSearch();
        return "http://" + os.getHost() + ":" + os.getMappedPort(9200);
    }
}
