# syntax=docker/dockerfile:1.7

# --------------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Wrapper and build scripts first: these change far less often than source, so a source-only
# edit reuses the cached dependency download instead of re-resolving the whole graph.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY modules/common/build.gradle.kts        modules/common/
COPY modules/ingestion/build.gradle.kts     modules/ingestion/
COPY modules/dedup/build.gradle.kts         modules/dedup/
COPY modules/search/build.gradle.kts        modules/search/
COPY modules/referral/build.gradle.kts      modules/referral/
COPY modules/trust/build.gradle.kts         modules/trust/
COPY modules/app/build.gradle.kts           modules/app/
COPY modules/benchmarks/build.gradle.kts    modules/benchmarks/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY modules ./modules

# Tests run in CI against real containers; repeating them here would need a Docker daemon
# inside the build and would double every image build.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar -x test

# Exploded layers: dependencies change rarely, application classes change every commit, so
# splitting them keeps the pushed delta to a few hundred kilobytes.
RUN java -Djarmode=tools -jar modules/app/build/libs/app.jar extract --layers --destination /extracted

# --------------------------------------------------------------------------------------------
# Runtime
# --------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 referralhub \
    && useradd --system --uid 1001 --gid referralhub --home /app referralhub

WORKDIR /app

COPY --from=build --chown=referralhub:referralhub /extracted/dependencies/ ./
COPY --from=build --chown=referralhub:referralhub /extracted/spring-boot-loader/ ./
COPY --from=build --chown=referralhub:referralhub /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=referralhub:referralhub /extracted/application/ ./

# Never root: a container escape should not start from uid 0.
USER referralhub

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
