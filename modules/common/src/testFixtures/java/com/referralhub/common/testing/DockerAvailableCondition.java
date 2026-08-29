package com.referralhub.common.testing;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/** Disables {@link RequiresDocker} tests when no daemon answers, instead of failing them. */
public class DockerAvailableCondition implements ExecutionCondition {

    private static final boolean DOCKER_AVAILABLE = probe();

    private static boolean probe() {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("REFERRALHUB_FORCE_DOCKER", "false"))) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return DOCKER_AVAILABLE
                ? ConditionEvaluationResult.enabled("Docker is available")
                : ConditionEvaluationResult.disabled(
                        "No Docker daemon reachable — integration test skipped. "
                                + "Run `docker compose up -d` or set REFERRALHUB_FORCE_DOCKER=true to force.");
    }
}
