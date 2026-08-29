package com.referralhub.referral.resume;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for resume bytes.
 *
 * <p>GCM rather than CBC because a resume that decrypts to attacker-chosen bytes is as bad as
 * one that leaks: authenticated encryption makes tampering a failure rather than a silent
 * success. The nonce is random per object and stored alongside the metadata; reusing a nonce
 * with the same key is the one mistake that breaks GCM completely, so it is never derived from
 * anything reusable like the object key.
 */
public final class ResumeCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public ResumeCipher(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "referralhub.storage.encryption-key must be base64-encoded", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "referralhub.storage.encryption-key must decode to 32 bytes (AES-256), got "
                            + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public record Encrypted(byte[] ciphertext, String nonceBase64) {
    }

    public Encrypted encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (Exception e) {
            throw new IllegalStateException("Resume encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, String nonceBase64) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonceBase64)));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            // Includes AEADBadTagException: the bytes were altered in the object store.
            throw new IllegalStateException("Resume decryption failed; object may be corrupt", e);
        }
    }

    /** Generates a key in the expected format. Used by tests and by the ops runbook. */
    public static String generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
