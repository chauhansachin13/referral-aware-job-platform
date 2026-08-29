package com.referralhub.referral.resume;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.ids.Ids;
import com.referralhub.referral.config.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Resume bytes: encrypted here, stored there, destroyed on request.
 *
 * <p>The object store never sees plaintext. That is the point of doing the crypto in the
 * application rather than relying on the bucket's server-side encryption: with SSE, anyone with
 * bucket credentials reads resumes, and "anyone with bucket credentials" grows over time.
 */
@Service
public class ResumeStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorage.class);

    private final S3Client s3;
    private final ResumeCipher cipher;
    private final StorageProperties properties;
    private final JdbcTemplate jdbc;

    public ResumeStorage(S3Client s3, ResumeCipher cipher, StorageProperties properties,
                         JdbcTemplate jdbc) {
        this.s3 = s3;
        this.cipher = cipher;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** Creates the bucket when it is missing. Idempotent; safe on every boot. */
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (S3Exception e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
            log.info("Created resume bucket {}", properties.getBucket());
        }
    }

    @Transactional
    public StoredResume store(UUID ownerId, String filename, String contentType, byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Resume is empty");
        }
        if (bytes.length > properties.getMaxResumeBytes()) {
            throw new IllegalArgumentException("Resume exceeds the "
                    + (properties.getMaxResumeBytes() / (1024 * 1024)) + " MB limit");
        }

        UUID id = Ids.next();
        // Owner id in the key, so an accidental bucket listing still cannot be joined to a
        // person without the database.
        String objectKey = "resumes/" + id + ".enc";
        String checksum = sha256(bytes);

        ResumeCipher.Encrypted encrypted = cipher.encrypt(bytes);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(objectKey)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(encrypted.ciphertext()));

        jdbc.update("""
                INSERT INTO resume (id, owner_id, object_key, filename, content_type, size_bytes,
                                    sha256, encryption_iv, key_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, objectKey, filename, contentType, bytes.length, checksum,
                encrypted.nonceBase64(), properties.getKeyId());

        return new StoredResume(id, ownerId, objectKey, filename, contentType, bytes.length,
                checksum, encrypted.nonceBase64(), properties.getKeyId(), Instant.now());
    }

    public Optional<StoredResume> findMetadata(UUID resumeId) {
        return jdbc.query("SELECT * FROM resume WHERE id = ?", (rs, rowNum) -> new StoredResume(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("object_key"),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getInt("size_bytes"),
                rs.getString("sha256"),
                rs.getString("encryption_iv"),
                rs.getString("key_id"),
                rs.getTimestamp("uploaded_at").toInstant()), resumeId).stream().findFirst();
    }

    /** Fetches and decrypts. Integrity is checked twice: by GCM's tag and by the stored digest. */
    public byte[] read(UUID resumeId) {
        StoredResume metadata = findMetadata(resumeId)
                .orElseThrow(() -> new NotFoundException("Resume", resumeId));

        ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(metadata.objectKey())
                .build());

        byte[] plaintext = cipher.decrypt(object.asByteArray(), metadata.encryptionIv());
        if (!sha256(plaintext).equals(metadata.sha256())) {
            throw new IllegalStateException("Resume " + resumeId + " failed its integrity check");
        }
        return plaintext;
    }

    /**
     * Destroys a resume: the object, then the row.
     *
     * <p>A hard delete, not a flag. Object first, so a failure part-way leaves a row pointing at
     * nothing (recoverable, visible) rather than an orphaned encrypted object nobody knows to
     * delete — which is exactly the state a deletion request is supposed to eliminate.
     */
    @Transactional
    public void hardDelete(UUID resumeId) {
        StoredResume metadata = findMetadata(resumeId)
                .orElseThrow(() -> new NotFoundException("Resume", resumeId));

        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(metadata.objectKey())
                .build());

        jdbc.update("UPDATE referral_request SET resume_id = NULL WHERE resume_id = ?", resumeId);
        jdbc.update("DELETE FROM resume WHERE id = ?", resumeId);

        log.info("Hard deleted resume {}", resumeId);
    }

    /** Every resume a person owns; the erasure path for a whole account. */
    @Transactional
    public int hardDeleteAllFor(UUID ownerId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM resume WHERE owner_id = ?",
                UUID.class, ownerId);
        ids.forEach(this::hardDelete);
        return ids.size();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    static String utf8(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
