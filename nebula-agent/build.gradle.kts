dependencies {
    implementation(project(":nebula-guard-api"))
    implementation("org.ow2.asm:asm:9.7.1")  // Java 21 compatible
    testImplementation(project(":nebula-core"))
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "org.nebula.agent.NebulaAgent",
            "Agent-Class" to "org.nebula.agent.NebulaAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}
