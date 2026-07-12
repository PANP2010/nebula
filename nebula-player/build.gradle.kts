plugins {
    id("java-library")
}

// nebula-player: Java 25 for nebula-folia-bridge dependency (PlayerPhysicsState, bridges).
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
    api(project(":nebula-core"))
    api(project(":nebula-entity"))
    api(project(":nebula-folia-bridge"))
    compileOnly(files(foliaApi))
    compileOnly(fileTree(foliaRuntime) { include("*.jar") })
    testImplementation(files(foliaApi))
    testImplementation(fileTree(foliaRuntime) { include("*.jar") })
    testRuntimeOnly(files(foliaApi))
    testRuntimeOnly(fileTree(foliaRuntime) { include("*.jar") })
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}
