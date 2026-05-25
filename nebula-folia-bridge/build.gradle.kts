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
    api(project(":nebula-core"))
    api(project(":nebula-guard-api"))
    implementation(project(":nebula-agent"))
    compileOnly("dev.folia:folia-api:1.21.4-R0.1-SNAPSHOT")
    testCompileOnly("dev.folia:folia-api:1.21.4-R0.1-SNAPSHOT")
}
