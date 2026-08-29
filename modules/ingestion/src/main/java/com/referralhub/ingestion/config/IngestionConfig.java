package com.referralhub.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

    /**
     * A builder rather than a client, so each collaborator applies its own defaults.
     *
     * <p>Timeouts are short and non-negotiable: a crawler that waits 30 seconds on one
     * unresponsive board is a crawler that stops crawling the other four thousand.
     */
    @Bean
    public RestClient.Builder ingestionRestClientBuilder() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory);
    }
}
