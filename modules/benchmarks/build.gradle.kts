plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ingestion"))
    implementation(project(":dedup"))
    implementation(project(":search"))
    implementation(project(":referral"))
    implementation(project(":trust"))

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.annprocess)
}

jmh {
    // ./gradlew :benchmarks:jmh -Pjmh.include=MatcherBenchmark runs one class instead of all of
    // them; the full suite takes several minutes.
    (project.findProperty("jmh.include") as String?)?.let { includes.set(listOf(it)) }
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
