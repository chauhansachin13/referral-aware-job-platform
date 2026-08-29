plugins { `java-library` }

dependencies {
    api(project(":common"))
    api(project(":ingestion"))
    implementation(libs.spring.boot.starter.web)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
