plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ingestion"))
    implementation(project(":dedup"))
    implementation(project(":search"))
    implementation(project(":referral"))
    implementation(project(":trust"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.prometheus)
    implementation(libs.springdoc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.spring.security.test)
    // Architecture rules live here because app is the only module whose classpath contains
    // all six feature modules at once, which is what makes the boundary rules checkable.
    testImplementation(libs.archunit)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
    // No launchScript(). It prepends a shell stub so the jar can be executed directly, which
    // makes the file no longer a plain zip — and `java -Djarmode=tools ... extract`, which the
    // Dockerfile uses for layer extraction, rejects it outright. The container's entrypoint is
    // `java -jar` regardless, so the stub buys nothing and cost an image build.
}

tasks.named<Jar>("jar") { enabled = false }
