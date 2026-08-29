package com.referralhub.trust.verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and constant-time checking of verification codes.
 *
 * <p>The code is never stored. What is stored is a salted SHA-256 of it, so a database dump does
 * not hand an attacker the ability to finish somebody else's employee verification and start
 * issuing referrals in their name.
 *
 * <p>Comparison is constant time. A timing oracle on a six-digit code is not theoretical: a
 * million-guess search collapses to a few thousand requests if the comparison leaks how many
 * leading digits were right.
 */
public final class OneTimeCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DIGITS = 6;

    private OneTimeCodes() {
    }

    public record Issued(String plaintext, String hash, String salt) {
    }

    public static Issued issue() {
        int value = RANDOM.nextInt(1_000_000);
        String plaintext = String.format("%0" + DIGITS + "d", value);
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        return new Issued(plaintext, hash(plaintext, salt), salt);
    }

    public static String hash(String plaintext, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    public static boolean matches(String candidate, String expectedHash, String salt) {
        if (candidate == null || expectedHash == null || salt == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(candidate, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
