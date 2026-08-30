plugins {
    `java-library`
    alias(libs.plugins.pitest)
}

dependencies {
    api(project(":common"))
    api(project(":trust"))
    // Referrals are placed against canonical jobs, so the matcher needs the deduplicated view
    // of a role rather than one board's copy of it.
    api(project(":dedup"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.aws.s3)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.spring.security.test)
}

/**
 * Mutation testing. Line coverage says a line ran; a mutation score says a test would have
 * noticed if that line were wrong — which is the property that actually matters for the
 * decision logic in this module.
 *
 * Opt-in (`./gradlew :referral:pitest`), not part of `check`: it re-runs the suite once per
 * surviving mutant and takes minutes rather than seconds.
 */
pitest {
    junit5PluginVersion.set(libs.versions.pitestJunit5)
    pitestVersion.set(libs.versions.pitest)
    targetClasses.set(listOf("com.referralhub.referral.*"))
    // Container-backed tests contribute nothing here and cannot run without a daemon.
    excludedTestClasses.set(listOf("*IT"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
}
