plugins {
    `java-library`
}

group = "org.nebula"

dependencies {
    api(project(":nebula-modules:nebula-core"))
    testImplementation(project(":nebula-modules:nebula-guard-api"))
}
