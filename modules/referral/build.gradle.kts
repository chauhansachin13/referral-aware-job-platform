plugins { `java-library` }

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
}
