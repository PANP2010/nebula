plugins {
    `java-library`
}

dependencies {
    // ASM is a transitive of the Paper/Folia server jars but is also pulled in directly
    // by nebula-agent, which we mirror here so the maintenance module can be used standalone
    // (e.g. from a CI job that only checks out nebula-maintenance).
    api(project(":nebula-core"))

    // ASM 9.9.x supports class file version 69 (Java 25), which is the
    // version the test classpath ships. nebula-agent still uses 9.7.1
    // (Java 21 compatible) because it instruments server classes only.
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")

    testImplementation("org.junit.jupiter:junit-jupiter")
}

description = "Phase 1.5 — annotation maintenance subsystem (MSD, regression runner, coverage dashboard)"
