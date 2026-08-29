package com.referralhub.referral.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeCryptoTest {

    private final ResumeCipher cipher = new ResumeCipher(ResumeCipher.generateKey());
    private static final byte[] RESUME = "Sachin Chauhan — Software Engineer\nExperience: ..."
            .getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a resume round-trips through encryption unchanged")
    void roundTrips() {
        ResumeCipher.Encrypted encrypted = cipher.encrypt(RESUME);

        assertThat(encrypted.ciphertext()).isNotEqualTo(RESUME);
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.nonceBase64()))
                .isEqualTo(RESUME);
    }

    @Test
    @DisplayName("every encryption uses a fresh nonce, which is what keeps GCM safe")
    void noncesAreNeverReused() {
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            nonces.add(cipher.encrypt(RESUME).nonceBase64());
        }
        assertThat(nonces).hasSize(500);
    }

    @Test
    @DisplayName("identical plaintexts produce different ciphertexts")
    void encryptionIsNonDeterministic() {
        assertThat(cipher.encrypt(RESUME).ciphertext())
                .isNotEqualTo(cipher.encrypt(RESUME).ciphertext());
    }

    @Test
    @DisplayName("a tampered ciphertext fails to decrypt rather than yielding altered bytes")
    void tamperingIsDetected() {
        ResumeCipher.Encrypted encrypted = cipher.encrypt(RESUME);
        byte[] tampered = encrypted.ciphertext().clone();
        tampered[3] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(tampered, encrypted.nonceBase64()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("a different key cannot read the object")
    void wrongKeyCannotDecrypt() {
        ResumeCipher.Encrypted encrypted = cipher.encrypt(RESUME);
        ResumeCipher other = new ResumeCipher(ResumeCipher.generateKey());

        assertThatThrownBy(() -> other.decrypt(encrypted.ciphertext(), encrypted.nonceBase64()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a short or malformed key is rejected at construction, not at first upload")
    void rejectsBadKeys() {
        assertThatThrownBy(() -> new ResumeCipher(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new ResumeCipher("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------------------------

    private static final String SECRET = "a-test-signing-secret-value";

    @Test
    @DisplayName("a signed download token round-trips")
    void tokenRoundTrips() {
        UUID resumeId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);

        String token = new ResumeAccessToken(resumeId, requestId, expiry).sign(SECRET);
        ResumeAccessToken verified = ResumeAccessToken.verify(token, SECRET);

        assertThat(verified.resumeId()).isEqualTo(resumeId);
        assertThat(verified.requestId()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("an expired token is refused even though its signature is valid")
    void expiredTokenIsRefused() {
        String token = new ResumeAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().minusSeconds(1)).sign(SECRET);

        assertThatThrownBy(() -> ResumeAccessToken.verify(token, SECRET))
                .isInstanceOf(ResumeAccessToken.InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("a token signed with another secret does not verify")
    void forgedSignatureIsRefused() {
        String token = new ResumeAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(300)).sign("attacker-secret");

        assertThatThrownBy(() -> ResumeAccessToken.verify(token, SECRET))
                .isInstanceOf(ResumeAccessToken.InvalidTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("editing the payload to point at another resume invalidates the token")
    void payloadTamperingIsDetected() {
        String token = new ResumeAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(300)).sign(SECRET);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (UUID.randomUUID() + "." + UUID.randomUUID() + "."
                        + Instant.now().plusSeconds(9999).toEpochMilli())
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ResumeAccessToken.verify(forgedPayload + "." + parts[1], SECRET))
                .isInstanceOf(ResumeAccessToken.InvalidTokenException.class);
    }

    @Test
    @DisplayName("garbage tokens produce a domain error, not a 500")
    void malformedTokensAreRejected() {
        for (String bad : new String[] {"", "abc", "a.b.c", "!!!.???", "onlyonepart"}) {
            assertThatThrownBy(() -> ResumeAccessToken.verify(bad, SECRET))
                    .isInstanceOf(ResumeAccessToken.InvalidTokenException.class);
        }
    }
}
