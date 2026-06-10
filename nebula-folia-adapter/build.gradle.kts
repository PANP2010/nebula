// nebula-folia-adapter: the NMS/Folia boundary. Compiles against the real
// Folia 26.1.2 API and therefore requires the Java 25 toolchain (the API is
// built for Java 25). Overrides the root subprojects {} Java-21 default.
//
// The Folia API jar is extracted from the bundled server at libs/ — see
// README. Pinned to the exact build the adapter targets
// (FoliaAdapterBoundary.TARGET_FOLIA_BUILD = 26.1.2.build.8-stable).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

val foliaApi = rootProject.layout.projectDirectory.file(
    "libs/folia-api-26.1.2.build.8-stable.jar")

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    compileOnly(files(foliaApi))
    testImplementation(files(foliaApi))
    testRuntimeOnly(files(foliaApi))
}

// Run tests on the Java 25 toolchain so the Java-25 Folia API classes load.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}
