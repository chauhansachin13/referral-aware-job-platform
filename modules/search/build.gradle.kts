plugins { `java-library` }

dependencies {
    api(project(":common"))
    api(project(":dedup"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.caffeine)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
