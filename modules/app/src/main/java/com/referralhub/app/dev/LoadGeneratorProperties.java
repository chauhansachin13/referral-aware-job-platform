package com.referralhub.app.dev;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the development-only corpus seeder and search load driver. */
@ConfigurationProperties(prefix = "referralhub.loadgen")
public class LoadGeneratorProperties {

    /** Seed a synthetic corpus on startup. Never enable this against a real database. */
    private boolean seedCorpus = false;

    /** Drive concurrent searches after seeding and report the latency distribution. */
    private boolean driveSearches = false;

    private int companies = 200;
    private int jobsPerCompany = 25;

    /** Fraction of postings that are near duplicates of an earlier one. */
    private double duplicateRate = 0.15;

    /** Fixed, so two runs produce the same corpus and benchmark numbers stay comparable. */
    private long seed = 20260829L;

    private int searchThreads = 16;
    private int searchesPerThread = 100;

    public boolean isSeedCorpus() {
        return seedCorpus;
    }

    public void setSeedCorpus(boolean seedCorpus) {
        this.seedCorpus = seedCorpus;
    }

    public boolean isDriveSearches() {
        return driveSearches;
    }

    public void setDriveSearches(boolean driveSearches) {
        this.driveSearches = driveSearches;
    }

    public int getCompanies() {
        return companies;
    }

    public void setCompanies(int companies) {
        this.companies = companies;
    }

    public int getJobsPerCompany() {
        return jobsPerCompany;
    }

    public void setJobsPerCompany(int jobsPerCompany) {
        this.jobsPerCompany = jobsPerCompany;
    }

    public double getDuplicateRate() {
        return duplicateRate;
    }

    public void setDuplicateRate(double duplicateRate) {
        this.duplicateRate = duplicateRate;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public int getSearchThreads() {
        return searchThreads;
    }

    public void setSearchThreads(int searchThreads) {
        this.searchThreads = searchThreads;
    }

    public int getSearchesPerThread() {
        return searchesPerThread;
    }

    public void setSearchesPerThread(int searchesPerThread) {
        this.searchesPerThread = searchesPerThread;
    }
}
