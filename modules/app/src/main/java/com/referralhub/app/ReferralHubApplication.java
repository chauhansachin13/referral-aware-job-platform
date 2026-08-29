package com.referralhub.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single deployable.
 *
 * <p>Six feature modules, one process, one database. A modular monolith rather than six services
 * because the module boundaries here are real (each owns its tables and its events) while the
 * operational cost of six deployments, six pipelines and six on-call surfaces would be entirely
 * imaginary at this scale. The Kafka topics between modules are what make splitting one out
 * later a deployment change rather than a rewrite.
 */
@SpringBootApplication(scanBasePackages = "com.referralhub")
@EnableScheduling
public class ReferralHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferralHubApplication.class, args);
    }
}
