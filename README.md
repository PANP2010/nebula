# Nebula Server

Phase -1 engineering skeleton for the Nebula deterministic multi-core Minecraft server architecture.

This repository starts with the smallest implementation surface needed to validate the architecture:

- `nebula-core`: RW-set and DAG primitives.
- `nebula-guard-api`: runtime RW-set integrity guard API.
- `nebula-agent`: Java Agent and ASM instrumentation skeleton.
- `nebula-folia-adapter`: Folia API boundary for the current target line.
- `nebula-folia-bridge`: lightweight bridge types for wiring Nebula beside Folia without touching NMS.

## Requirements

For the active root Gradle build:

- Gradle Wrapper: `8.13`
- Java toolchain: `21`

Folia/Paper upstream work and the disabled `nebula-folia-adapter` module require Java 25 and the target Folia coordinate `dev.folia:folia-api:[26.1.2.build,)`.

Run the first verification pass with:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test
```

On Windows:

```powershell
.\gradlew.bat test
```
