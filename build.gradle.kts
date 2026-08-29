plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot) apply false
}

val springBootVersion = libs.versions.springBoot.get()
val awsSdkVersion = libs.versions.awsSdk.get()
val jqwikVersion = libs.versions.jqwik.get()

allprojects {
    group = "com.referralhub"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        // The version-catalog accessor is not available inside `subprojects {}`,
        // so the shared platform + test wiring is declared with literal coordinates.
        val bootBom = "org.springframework.boot:spring-boot-dependencies:$springBootVersion"
        val awsBom = "software.amazon.awssdk:bom:$awsSdkVersion"

        add("implementation", platform(bootBom))
        add("implementation", platform(awsBom))
        add("annotationProcessor", platform(bootBom))
        add("testImplementation", platform(bootBom))
        add("testImplementation", platform(awsBom))

        add("compileOnly", "org.springframework.boot:spring-boot-configuration-processor")
        add("annotationProcessor", "org.springframework.boot:spring-boot-configuration-processor")

        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testImplementation", "org.assertj:assertj-core")
        add("testImplementation", "org.mockito:mockito-junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

        // Property-based tests run on their own JUnit Platform engine alongside Jupiter.
        add("testImplementation", "net.jqwik:jqwik:$jqwikVersion")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
    }

    tasks.withType<Test>().configureEach {
        // Three engines: Jupiter for example-based tests, jqwik for property-based ones, and
        // ArchUnit for the architecture rules. Left unfiltered on purpose — an explicit
        // includeEngines list silently discovers zero tests the moment an engine is added.
        useJUnitPlatform()
        // Integration tests self-disable when no Docker daemon is reachable
        // (see common's DockerAvailable condition), so this stays green offline.
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
        maxHeapSize = "2g"
    }

    tasks.named<Test>("test") {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

// Convenience: aggregate every module's tests from the root.
tasks.register("allTests") {
    group = "verification"
    description = "Runs the test task of every module."
    dependsOn(subprojects.map { "${it.path}:test" })
}
