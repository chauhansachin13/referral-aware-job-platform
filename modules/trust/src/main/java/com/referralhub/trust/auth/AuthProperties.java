package com.referralhub.trust.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "referralhub.auth")
public class AuthProperties {

    /**
     * HMAC secret for signing access tokens. At least 32 bytes.
     *
     * <p>No default, like the resume secrets: a shipped default signing key means anyone can
     * mint a token for any account on any deployment that forgot to override it.
     */
    private String jwtSecret;

    /** How long an access token stays valid. */
    private Duration tokenTtl = Duration.ofHours(12);

    private String issuer = "referralhub";

    /**
     * Creates an administrator on startup if no user holds that role.
     *
     * <p>Off unless both fields are set. Someone has to be able to register the first ATS board,
     * and the alternative — a hardcoded account, or an open admin endpoint — is worse.
     */
    private String bootstrapAdminEmail;
    private String bootstrapAdminPassword;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getBootstrapAdminEmail() {
        return bootstrapAdminEmail;
    }

    public void setBootstrapAdminEmail(String bootstrapAdminEmail) {
        this.bootstrapAdminEmail = bootstrapAdminEmail;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }
}
