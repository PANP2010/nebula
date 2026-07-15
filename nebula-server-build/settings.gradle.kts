pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "nebula"

include("nebula-api")
include("nebula-server")

// Nebula engine modules (compiled into server jar)
include("nebula-modules:nebula-core")
include("nebula-modules:nebula-guard-api")
include("nebula-modules:nebula-redstone")
include("nebula-modules:nebula-entity")
include("nebula-modules:nebula-replay")

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val nebulaChannel = providers.gradleProperty("channel").get().trim()
    val nebulaBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (nebulaBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$nebulaBuildNumber-${nebulaChannel.lowercase()}"
    }
    version = versionString
}
