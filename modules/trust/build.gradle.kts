plugins { `java-library` }

dependencies {
    api(project(":common"))
    // The company registry (name, slug, verified email domain) lives in ingestion because that
    // is where companies are first discovered. Trust reads it; it does not crawl anything.
    api(project(":ingestion"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
