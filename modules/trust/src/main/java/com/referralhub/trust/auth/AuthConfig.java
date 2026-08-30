package com.referralhub.trust.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    /**
     * BCrypt at strength 12.
     *
     * <p>The default of 10 is a decade-old compromise. Twelve costs roughly a quarter of a
     * second per login on current hardware, which nobody notices, and multiplies the cost of an
     * offline attack against a stolen table by four.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static SecretKey signingKey(AuthProperties properties) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    referralhub.auth.jwt-secret is not set.
                    A default signing key would let anyone mint a token for any account on every
                    deployment that forgot to override it, so there is not one. Generate one with:
                      openssl rand -base64 48
                    and export it as REFERRALHUB_AUTH_JWTSECRET.""");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 with a key shorter than its output is weaker than the algorithm implies.
            throw new IllegalStateException(
                    "referralhub.auth.jwt-secret must be at least 32 bytes for HS256, got "
                            + bytes.length);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(signingKey(properties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        return NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
