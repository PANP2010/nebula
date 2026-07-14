import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.4.2"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val foliaApi = rootProject.layout.projectDirectory.file(
    "libs/folia-api-26.1.2.build.8-stable.jar")
val foliaRuntime = rootProject.layout.projectDirectory.dir("libs/folia-runtime")

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    implementation(project(":nebula-folia-bridge"))
    implementation(project(":nebula-folia-adapter"))
    implementation(project(":nebula-redstone"))   // For RedstoneWorldState
    implementation(project(":nebula-entity"))     // For EntityPhysicsState, BlockEntityState
    implementation(project(":nebula-replay"))     // For ReplayRecorder, ReplayFrame
    implementation(project(":nebula-player"))     // Player DAG executor (MOVE, BLOCK_INTERACT)
    compileOnly(files(foliaApi))
    compileOnly(fileTree(foliaRuntime) { include("*.jar") })
    testImplementation(files(foliaApi))
    testImplementation(fileTree(foliaRuntime) { include("*.jar") })
    testRuntimeOnly(files(foliaApi))
    testRuntimeOnly(fileTree(foliaRuntime) { include("*.jar") })
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("nebula-plugin")
    relocate("org.nebula.core", "shaded.nebula.core")
    relocate("org.nebula.guard", "shaded.nebula.guard")
    relocate("org.nebula.folia", "shaded.nebula.folia")
    relocate("org.nebula.annotations", "shaded.nebula.annotations")
    relocate("org.nebula.player", "shaded.nebula.player")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
