plugins { `java-library` }

dependencies {
    api(project(":common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
