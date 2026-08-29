package com.referralhub.dedup.config;

import com.referralhub.dedup.minhash.LshBanding;
import com.referralhub.dedup.minhash.MinHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DedupProperties.class)
public class DedupConfig {

    /** Shared and stateless. The permutation coefficients must be identical across the fleet. */
    @Bean
    public MinHasher minHasher(DedupProperties properties) {
        return new MinHasher(properties.getNumHashes(), properties.getHashSeed());
    }

    @Bean
    public LshBanding lshBanding(DedupProperties properties) {
        return LshBanding.of(properties.getNumHashes(), properties.getBands());
    }
}
