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
// The API jar references transitive types (adventure-text, JetBrains
// annotations, etc.). The full set was extracted from the bundled server's
// META-INF/libraries into libs/folia-runtime/ — see libs/README.md.
val foliaRuntime = rootProject.layout.projectDirectory.dir("libs/folia-runtime")

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    implementation(project(":nebula-folia-bridge"))
    implementation(project(":nebula-redstone"))  // For RedstoneWorldState CAS store
    implementation(project(":nebula-entity"))    // For EntityPhysicsState + BlockEntityState + Vec3
    compileOnly(files(foliaApi))
    compileOnly(fileTree(foliaRuntime) { include("*.jar") })
    // Guava is referenced by Bukkit Material annotations but not bundled in folia-runtime
    compileOnly("com.google.guava:guava:33.4.0-jre")
    testImplementation(files(foliaApi))
    testImplementation(fileTree(foliaRuntime) { include("*.jar") })
    testImplementation("com.google.guava:guava:33.4.0-jre")
    testRuntimeOnly(files(foliaApi))
    testRuntimeOnly(fileTree(foliaRuntime) { include("*.jar") })
    testRuntimeOnly("com.google.guava:guava:33.4.0-jre")
}

// Run tests on the Java 25 toolchain so the Java-25 Folia API classes load.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}
