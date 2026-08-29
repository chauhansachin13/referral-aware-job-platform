plugins { `java-library` }

dependencies {
    api(project(":common"))
    api(project(":trust"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.aws.s3)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":common")))
}
