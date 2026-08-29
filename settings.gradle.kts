pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "referral-aware-job-platform"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

listOf("common", "ingestion", "dedup", "search", "referral", "trust", "app", "benchmarks").forEach { module ->
    include(":$module")
    project(":$module").projectDir = file("modules/$module")
}
