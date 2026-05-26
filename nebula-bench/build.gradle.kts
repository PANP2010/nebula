plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":nebula-core"))
    implementation(project(":nebula-guard-api"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
