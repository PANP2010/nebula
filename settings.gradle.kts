pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "nebula-server"

include(
    "nebula-core",
    "nebula-guard-api",
    "nebula-agent",
    "nebula-redstone",
    "nebula-entity",
    "nebula-replay",
    "nebula-folia-bridge",
    "nebula-plugin",
    "nebula-bench",
    "nebula-integration",
    "nebula-folia-adapter",  // Java 25 + Folia 26.1.2 API (libs/folia-api-*.jar)
)
