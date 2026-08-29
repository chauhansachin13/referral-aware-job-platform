package com.referralhub.referral.config;

import com.referralhub.referral.resume.ResumeCipher;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class ReferralConfig {

    /**
     * Fails fast when the crypto secrets are missing.
     *
     * <p>Refusing to start beats starting with a placeholder key: the second option produces a
     * system that appears to work while writing resumes that anyone with the source can decrypt.
     */
    @Bean
    public ResumeCipher resumeCipher(StorageProperties properties) {
        if (properties.getEncryptionKey() == null || properties.getEncryptionKey().isBlank()) {
            throw new IllegalStateException("""
                    referralhub.storage.encryption-key is not set.
                    Resumes are PII and are encrypted before they leave this process, so there is
                    no safe default. Generate one with:
                      openssl rand -base64 32
                    and export it as REFERRALHUB_STORAGE_ENCRYPTIONKEY.""");
        }
        if (properties.getUrlSigningSecret() == null || properties.getUrlSigningSecret().isBlank()) {
            throw new IllegalStateException(
                    "referralhub.storage.url-signing-secret is not set; download links cannot be "
                            + "signed. Export REFERRALHUB_STORAGE_URLSIGNINGSECRET.");
        }
        return new ResumeCipher(properties.getEncryptionKey());
    }

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(),
                                properties.getSecretKey())))
                // MinIO serves buckets as a path, not as a DNS subdomain.
                .forcePathStyle(true)
                .build();
    }
}
