plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// Focused baseline configuration for the DAG-build cost study (see
// docs/profiling/dag-build-baseline-*.md). Run a narrower, faster sweep with:
//   ./gradlew :nebula-bench:jmh -Pdagbaseline
// Without the flag, `jmh` runs the full matrix declared on each @Benchmark.
if (project.hasProperty("dagbaseline")) {
    jmh {
        includes.set(listOf("DagBuildBenchmark\\.buildWith.*"))
        warmupIterations.set(2)
        iterations.set(3)
        fork.set(1)
        timeOnIteration.set("500ms")
        warmup.set("500ms")
        benchmarkParameters.set(
            mapOf(
                "taskCount" to objects.listProperty<String>().value(listOf("500", "4000", "8000")),
                "workload" to objects.listProperty<String>().value(listOf("redstone", "entityOnly", "conflictHeavy")),
                "bucketSize" to objects.listProperty<String>().value(listOf("128")),
            )
        )
    }
}
