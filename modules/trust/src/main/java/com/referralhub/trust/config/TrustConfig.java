package com.referralhub.trust.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrustProperties.class)
public class TrustConfig {
}
