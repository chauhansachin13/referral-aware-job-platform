package com.referralhub.referral.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage and resume-crypto settings.
 *
 * <p>{@code encryptionKey} and {@code urlSigningSecret} have no defaults on purpose. A default
 * secret is worse than no secret: it ships, nobody notices, and every deployment shares it. The
 * application refuses to start without them.
 */
@ConfigurationProperties(prefix = "referralhub.storage")
public class StorageProperties {

    private String endpoint = "http://localhost:9000";
    private String region = "us-east-1";
    private String bucket = "referralhub-resumes";
    private String accessKey;
    private String secretKey;

    /** Base64-encoded 32-byte AES key. Env only: REFERRALHUB_STORAGE_ENCRYPTIONKEY. */
    private String encryptionKey;

    /** Identifier written next to each object so a key rotation is traceable. */
    private String keyId = "resume-key-v1";

    /** HMAC secret for download URLs. Env only: REFERRALHUB_STORAGE_URLSIGNINGSECRET. */
    private String urlSigningSecret;

    /** How long a minted download URL stays valid. */
    private Duration downloadUrlTtl = Duration.ofMinutes(5);

    /** Largest resume accepted, in bytes. */
    private long maxResumeBytes = 10L * 1024 * 1024;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getUrlSigningSecret() {
        return urlSigningSecret;
    }

    public void setUrlSigningSecret(String urlSigningSecret) {
        this.urlSigningSecret = urlSigningSecret;
    }

    public Duration getDownloadUrlTtl() {
        return downloadUrlTtl;
    }

    public void setDownloadUrlTtl(Duration downloadUrlTtl) {
        this.downloadUrlTtl = downloadUrlTtl;
    }

    public long getMaxResumeBytes() {
        return maxResumeBytes;
    }

    public void setMaxResumeBytes(long maxResumeBytes) {
        this.maxResumeBytes = maxResumeBytes;
    }
}
