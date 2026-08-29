package com.referralhub.dedup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every dial in the deduplicator.
 *
 * <p>These are configuration and not constants because the right values depend on the corpus.
 * The precision test asserts against {@link #getTargetPrecision()}, so tightening the threshold
 * and re-running the labelled set is a one-line experiment rather than a code change.
 */
@ConfigurationProperties(prefix = "referralhub.dedup")
public class DedupProperties {

    /** Signature length. More hashes means a better Jaccard estimate and a bigger index. */
    private int numHashes = 128;

    /** Bands the signature is split into. With 128 hashes, 16 bands => 8 rows => s ~= 0.71. */
    private int bands = 16;

    /** Fixed so signatures stay comparable across restarts and deployments. */
    private long hashSeed = 0x5EED_1234L;

    /** Word shingle size for the description. */
    private int shingleSize = 3;

    /** Score at or above which two postings are declared the same canonical job. */
    private double matchThreshold = 0.82;

    /** The precision the labelled fixture set must still meet for the build to pass. */
    private double targetPrecision = 0.95;

    /** Recall floor on the same fixture set. */
    private double targetRecall = 0.80;

    /** Hard cap on candidates retrieved per posting, so one huge bucket cannot stall ingestion. */
    private int maxCandidates = 200;

    /**
     * How many of those candidates earn an exact Jaccard.
     *
     * <p>Retrieval is cheap and imprecise, exact scoring is precise and expensive. Candidates
     * are ranked by matched bands and then by their MinHash estimate — both computed from data
     * already in hand — and only this many survivors pay for re-shingling a description.
     */
    private int exactScoreLimit = 25;

    private final Weights weights = new Weights();

    public int getNumHashes() {
        return numHashes;
    }

    public void setNumHashes(int numHashes) {
        this.numHashes = numHashes;
    }

    public int getBands() {
        return bands;
    }

    public void setBands(int bands) {
        this.bands = bands;
    }

    public long getHashSeed() {
        return hashSeed;
    }

    public void setHashSeed(long hashSeed) {
        this.hashSeed = hashSeed;
    }

    public int getShingleSize() {
        return shingleSize;
    }

    public void setShingleSize(int shingleSize) {
        this.shingleSize = shingleSize;
    }

    public double getMatchThreshold() {
        return matchThreshold;
    }

    public void setMatchThreshold(double matchThreshold) {
        this.matchThreshold = matchThreshold;
    }

    public double getTargetPrecision() {
        return targetPrecision;
    }

    public void setTargetPrecision(double targetPrecision) {
        this.targetPrecision = targetPrecision;
    }

    public double getTargetRecall() {
        return targetRecall;
    }

    public void setTargetRecall(double targetRecall) {
        this.targetRecall = targetRecall;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getExactScoreLimit() {
        return exactScoreLimit;
    }

    public void setExactScoreLimit(int exactScoreLimit) {
        this.exactScoreLimit = exactScoreLimit;
    }

    public Weights getWeights() {
        return weights;
    }

    /** Must sum to 1.0; {@link com.referralhub.dedup.match.DuplicateScorer} asserts it. */
    public static class Weights {

        private double jaccard = 0.45;
        private double title = 0.30;
        private double company = 0.15;
        private double location = 0.10;

        public double getJaccard() {
            return jaccard;
        }

        public void setJaccard(double jaccard) {
            this.jaccard = jaccard;
        }

        public double getTitle() {
            return title;
        }

        public void setTitle(double title) {
            this.title = title;
        }

        public double getCompany() {
            return company;
        }

        public void setCompany(double company) {
            this.company = company;
        }

        public double getLocation() {
            return location;
        }

        public void setLocation(double location) {
            this.location = location;
        }

        public double sum() {
            return jaccard + title + company + location;
        }
    }
}
