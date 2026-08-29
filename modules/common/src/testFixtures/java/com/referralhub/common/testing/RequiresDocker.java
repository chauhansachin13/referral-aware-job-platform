package com.referralhub.common.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test that needs a real Docker daemon.
 *
 * <p>The point is not to make integration tests optional — CI always has Docker and always runs
 * them. It is that a contributor on a laptop without Docker gets a green {@code ./gradlew test}
 * with honest "skipped" lines instead of a wall of connection errors that trains people to
 * ignore test output.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerAvailableCondition.class)
public @interface RequiresDocker {
}
