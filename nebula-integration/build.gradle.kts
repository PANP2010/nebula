// Cross-subsystem integration tests: exercises redstone + entity physics
// running together through one combined DAG tick. Test-only module (no main
// sources) so it can depend on both subsystems without creating a cycle.
dependencies {
    testImplementation(project(":nebula-core"))
    testImplementation(project(":nebula-redstone"))
    testImplementation(project(":nebula-entity"))
    testImplementation(project(":nebula-replay"))
}
