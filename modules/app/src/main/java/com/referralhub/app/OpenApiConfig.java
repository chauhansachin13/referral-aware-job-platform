package com.referralhub.app;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI referralHubApi() {
        return new OpenAPI().info(new Info()
                .title("Referral-Aware Job Discovery Platform")
                .version("0.1.0")
                .description("""
                        Ingests postings from public ATS boards (Greenhouse, Lever, Ashby),
                        deduplicates them across sources, ranks them with hybrid retrieval, and
                        runs a two-sided referral marketplace between seekers and verified
                        employees.""")
                .license(new License().name("MIT")));
    }
}
