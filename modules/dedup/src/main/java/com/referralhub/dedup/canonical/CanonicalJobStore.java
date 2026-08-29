package com.referralhub.dedup.canonical;

import com.referralhub.common.ids.Ids;
import com.referralhub.dedup.match.JobFingerprint;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Canonical jobs, their backing sources, and the LSH band index. */
@Repository
public class CanonicalJobStore {

    private static final RowMapper<CanonicalJob> JOB_MAPPER = (rs, rowNum) -> new CanonicalJob(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("title"),
            rs.getString("canonical_role"),
            rs.getString("canonical_level"),
            rs.getString("specialization"),
            rs.getString("description_html"),
            rs.getString("location"),
            rs.getBoolean("remote"),
            readSignature(rs, "signature"),
            rs.getInt("source_count"),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());

    private final JdbcTemplate jdbc;

    public CanonicalJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CanonicalJob> findById(UUID id) {
        return jdbc.query("SELECT * FROM canonical_job WHERE id = ?", JOB_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<UUID> findCanonicalIdForPosting(UUID rawPostingId) {
        return jdbc.queryForList(
                        "SELECT canonical_job_id FROM job_source WHERE raw_posting_id = ?",
                        UUID.class, rawPostingId)
                .stream().findFirst();
    }

    /** Creates a canonical job from the posting that first revealed it. */
    public UUID create(JobFingerprint fingerprint, String descriptionHtml) {
        UUID id = Ids.next();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO canonical_job
                        (id, company_id, title, canonical_role, canonical_level, specialization,
                         description_html, location, remote, signature, source_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, fingerprint.companyId());
            ps.setString(3, fingerprint.rawTitle());
            ps.setString(4, fingerprint.title().role());
            ps.setString(5, fingerprint.title().level().name());
            ps.setString(6, fingerprint.title().specialization());
            ps.setString(7, descriptionHtml);
            ps.setString(8, fingerprint.location());
            ps.setBoolean(9, fingerprint.remote());
            ps.setArray(10, toSqlArray(connection, fingerprint.signature()));
            return ps;
        });
        return id;
    }

    /** Writes one row per band so any single band match makes this job a candidate. */
    public void indexBands(UUID canonicalJobId, UUID companyId, long[] bandHashes) {
        List<Object[]> batch = new ArrayList<>(bandHashes.length);
        for (int band = 0; band < bandHashes.length; band++) {
            batch.add(new Object[] {(short) band, bandHashes[band], canonicalJobId, companyId});
        }
        jdbc.batchUpdate("""
                INSERT INTO lsh_bucket (band_index, band_hash, canonical_job_id, company_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, batch);
    }

    /**
     * Candidate generation: canonical jobs at the same company sharing at least one band.
     *
     * <p>Ordered by how many bands matched, because agreeing on six bands out of sixteen is much
     * stronger evidence than agreeing on one, and the caller only exact-scores a prefix of this
     * list.
     */
    public List<CandidateRow> findCandidates(UUID companyId, long[] bandHashes, int limit) {
        if (bandHashes.length == 0) {
            return List.of();
        }
        StringBuilder tuples = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        for (int band = 0; band < bandHashes.length; band++) {
            if (band > 0) {
                tuples.append(", ");
            }
            tuples.append("(?, ?)");
            args.add((short) band);
            args.add(bandHashes[band]);
        }
        args.add(limit);

        String sql = """
                SELECT c.id, c.company_id, c.title, c.description_html, c.location, c.remote,
                       c.signature, count(*) AS matched_bands
                FROM lsh_bucket b
                JOIN canonical_job c ON c.id = b.canonical_job_id
                WHERE b.company_id = ?
                  AND c.closed_at IS NULL
                  AND (b.band_index, b.band_hash) IN (%s)
                GROUP BY c.id
                ORDER BY matched_bands DESC, c.last_seen_at DESC
                LIMIT ?
                """.formatted(tuples);

        return jdbc.query(sql, (rs, rowNum) -> new CandidateRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("title"),
                rs.getString("description_html"),
                rs.getString("location"),
                rs.getBoolean("remote"),
                readSignature(rs, "signature"),
                rs.getInt("matched_bands")), args.toArray());
    }

    /** Attaches a posting to a canonical job. Re-running the same event is a no-op update. */
    public void attachSource(UUID canonicalJobId, UUID rawPostingId, String source,
                             String externalId, String applyUrl, double score) {
        jdbc.update("""
                INSERT INTO job_source
                    (id, canonical_job_id, raw_posting_id, source, external_id, apply_url, match_score)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (raw_posting_id) DO UPDATE SET
                    canonical_job_id = excluded.canonical_job_id,
                    apply_url = excluded.apply_url,
                    match_score = excluded.match_score,
                    attached_at = now()
                """, Ids.next(), canonicalJobId, rawPostingId, source, externalId, applyUrl, score);

        jdbc.update("""
                UPDATE canonical_job
                SET source_count = (SELECT count(*) FROM job_source WHERE canonical_job_id = ?),
                    last_seen_at = now()
                WHERE id = ?
                """, canonicalJobId, canonicalJobId);
    }

    public List<CanonicalJob> findByCompany(UUID companyId, int limit) {
        return jdbc.query("""
                SELECT * FROM canonical_job
                WHERE company_id = ? AND closed_at IS NULL
                ORDER BY last_seen_at DESC LIMIT ?
                """, JOB_MAPPER, companyId, limit);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM canonical_job", Integer.class);
        return n == null ? 0 : n;
    }

    private static Array toSqlArray(java.sql.Connection connection, int[] signature)
            throws SQLException {
        Integer[] boxed = new Integer[signature.length];
        for (int i = 0; i < signature.length; i++) {
            boxed[i] = signature[i];
        }
        return connection.createArrayOf("integer", boxed);
    }

    private static int[] readSignature(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return new int[0];
        }
        Integer[] boxed = (Integer[]) array.getArray();
        int[] signature = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            signature[i] = boxed[i] == null ? Integer.MAX_VALUE : boxed[i];
        }
        return signature;
    }
}
