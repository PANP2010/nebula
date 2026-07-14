plugins {
    `java-library`
    id("jacoco")
}

group = "org.nebula"
version = "0.2.0"

val junitVersion = "5.11.4"
val safeBuildRoot = file("${System.getProperty("user.home")}/.gradle/nebula-server-build/${rootProject.name}")

// Configure JaCoCo at the root so every subproject inherits the same
// toolchain version. The Nebula Regression workflow (P1.5.2) uploads the
// resulting XML/HTML under the `coverage-reports` artifact.
extensions.configure(JacocoPluginExtension::class) {
    toolVersion = "0.8.12"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    group = rootProject.group
    version = rootProject.version
    layout.buildDirectory.set(safeBuildRoot.resolve(path.removePrefix(":").replace(':', '-')))

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Long-running DG1-scale replays are tagged "slow" and skipped by
            // default. Run them with: ./gradlew test -Pslow
            if (!project.hasProperty("slow")) {
                excludeTags("slow")
            }
        }
        // Emit coverage data on every Test run so `jacocoTestReport` always
        // has something to aggregate, even in CI failures.
        extensions.configure(JacocoTaskExtension::class) {
            isEnabled = true
        }
    }

    // Hook every Test task into jacocoTestReport so `./gradlew jacocoTestReport`
    // (used by the CI workflow) always rebuilds from current results.
    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
