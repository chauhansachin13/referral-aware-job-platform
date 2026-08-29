plugins { `java-library` }

dependencies {
    api(project(":common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.resilience4j.spring.boot3)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
