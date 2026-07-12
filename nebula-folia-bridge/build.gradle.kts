// nebula-folia-bridge: abstractions over Folia API for Nebula subsystems.
// Uses Java 25 + Folia 26.1.2 API to match the adapter.
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
    api(project(":nebula-guard-api"))
    api(project(":nebula-entity"))  // EntitySnapshot for the entity tick hook (B8 C1)
    implementation(project(":nebula-agent"))
    implementation(project(":nebula-replay"))  // For capture harness
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
