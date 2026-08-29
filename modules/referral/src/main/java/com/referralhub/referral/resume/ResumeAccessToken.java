package com.referralhub.referral.resume;

import com.referralhub.common.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A short-lived, HMAC-signed capability to read exactly one resume.
 *
 * <p>Deliberately our own token rather than an S3 presigned URL. A presigned URL requires the
 * object store to hold plaintext (otherwise the browser downloads ciphertext), cannot be
 * revoked before it expires, and moves the access decision outside the application where none of
 * it is audited. A token we mint and verify keeps the key, the revocation check and the audit
 * trail on this side of the boundary.
 *
 * <p>The referral request id is bound into the signature, so a token minted while a request was
 * ACCEPTED is checked against that request's state again at redemption time.
 */
public record ResumeAccessToken(UUID resumeId, UUID requestId, Instant expiresAt) {

    public static class InvalidTokenException extends DomainException {
        public InvalidTokenException(String message) {
            super("invalid_resume_token", message);
        }
    }

    public String sign(String secret) {
        String payload = resumeId + "." + requestId + "." + expiresAt.toEpochMilli();
        return base64(payload) + "." + base64Raw(hmac(payload, secret));
    }

    public static ResumeAccessToken verify(String token, String secret) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 2) {
            throw new InvalidTokenException("Malformed download token");
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Malformed download token");
        }

        // Constant time: a byte-by-byte comparison leaks how much of a forged signature was right.
        if (!java.security.MessageDigest.isEqual(signature, hmac(payload, secret))) {
            throw new InvalidTokenException("Download token signature does not verify");
        }

        String[] fields = payload.split("\\.");
        if (fields.length != 3) {
            throw new InvalidTokenException("Malformed download token");
        }
        Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[2]));
        if (expiresAt.isBefore(Instant.now())) {
            throw new InvalidTokenException("Download link has expired; ask for a fresh one");
        }
        return new ResumeAccessToken(UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                expiresAt);
    }

    private static byte[] hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign resume access token", e);
        }
    }

    private static String base64(String value) {
        return base64Raw(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Raw(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
