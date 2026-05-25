dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    compileOnly("dev.folia:folia-api:[26.1.2.build,)")
}

sourceSets {
    test {
        java {
            srcDir("src/test/java")
        }
    }
}
