package com.referralhub.common.error;

/**
 * A downstream this request needed is not reachable.
 *
 * <p>Distinct from the catch-all on purpose. A 500 says "this service has a bug"; a 503 says
 * "a dependency is down, the request is worth retrying". They lead a caller — and whoever is on
 * call — to different conclusions, and answering "Something went wrong" when OpenSearch is
 * simply not running sends both to the wrong place.
 */
public class DependencyUnavailableException extends DomainException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super("dependency_unavailable", dependency + " is not reachable right now");
        this.dependency = dependency;
        initCause(cause);
    }

    public String dependency() {
        return dependency;
    }
}
