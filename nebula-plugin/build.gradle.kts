import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    implementation(project(":nebula-redstone"))
    implementation(project(":nebula-folia-bridge"))
    implementation(project(":nebula-replay"))
    compileOnly("dev.folia:folia-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("nebula-plugin")
    relocate("org.nebula.core", "shaded.nebula.core")
    relocate("org.nebula.guard", "shaded.nebula.guard")
    relocate("org.nebula.redstone", "shaded.nebula.redstone")
    relocate("org.nebula.replay", "shaded.nebula.replay")
    relocate("org.nebula.annotations", "shaded.nebula.annotations")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
