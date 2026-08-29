package com.referralhub.referral.resume;

import java.time.Instant;
import java.util.UUID;

public record StoredResume(
        UUID id,
        UUID ownerId,
        String objectKey,
        String filename,
        String contentType,
        int sizeBytes,
        String sha256,
        String encryptionIv,
        String keyId,
        Instant uploadedAt) {
}
