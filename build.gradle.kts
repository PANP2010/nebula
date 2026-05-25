plugins {
    `java-library`
}

group = "org.nebula"
version = "0.1.0-SNAPSHOT"

val junitVersion = "5.11.4"
val safeBuildRoot = file("${System.getProperty("user.home")}/.gradle/nebula-server-build/${rootProject.name}")

subprojects {
    apply(plugin = "java-library")

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
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
