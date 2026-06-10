# Local libraries & the Folia adapter

## `folia-api-26.1.2.build.8-stable.jar`

Extracted from the bundled Folia server (`folia-26.1.2-8.jar`) via:

```bash
unzip -o -j folia-26.1.2-8.jar \
  "META-INF/libraries/dev/folia/folia-api/26.1.2.build.8-stable/folia-api-26.1.2.build.8-stable.jar" \
  -d libs/
```

This is the exact API build the adapter targets
(`FoliaAdapterBoundary.TARGET_FOLIA_BUILD = "26.1.2.build.8-stable"`).

The classes are **Java 25 bytecode** (class-file major version 69).

## Enabling `nebula-folia-adapter` — DONE

The module is **enabled and building** against the real Folia 26.1.2 API under
JDK 25. The environment now has everything:
- ✅ Folia 26.1.2 API jar (here) + a runnable `folia-26.1.2-8.jar` server
- ✅ Decompiled MC 26.1.2 Mojmaps sources (`decompiled MC/`)
- ✅ A full **JDK 25** at `/home/kuli/jdks/jdk-25.0.3` (with `javac`)

`gradle.properties` pins the toolchain paths (JDK 25 + JDK 21) and disables
auto-download (the official toolchain source is slow/blocked here). The adapter
build script sets the Java 25 toolchain for both compile and test; everything
else stays on Java 21. `./gradlew test` builds all 10 modules green.

### Re-extracting the API jar (if needed)

```bash
unzip -o -j folia-26.1.2-8.jar \
  "META-INF/libraries/dev/folia/folia-api/26.1.2.build.8-stable/folia-api-26.1.2.build.8-stable.jar" \
  -d libs/
```

This is the exact API build the adapter targets
(`FoliaAdapterBoundary.TARGET_FOLIA_BUILD = "26.1.2.build.8-stable"`),
Java 25 bytecode (class-file major version 69).

### Transitive runtime libraries (`libs/folia-runtime/`)

The API jar references types from adventure-text, JetBrains annotations, etc.
Those are extracted from the bundled server's `META-INF/libraries`:

```bash
mkdir -p libs/folia-runtime
unzip -o -q -j folia-26.1.2-8.jar "META-INF/libraries/*.jar" -d libs/folia-runtime/
```

Two logging APIs the server provides on its own classpath (not in the bundle)
are copied in so proxy-based tests can resolve `org.bukkit.Plugin`/`World`
method return types: `slf4j-api` and `log4j-api` (any recent version). The
adapter build adds this whole directory to the compile + test classpath. Both
`libs/*.jar` and `libs/folia-runtime/` are gitignored (large binaries).

### Note on detection markers

`RegionizedServer` is a Folia **server-internal** class — NOT in the API jar.
`FoliaRuntimeDetector.isFoliaRuntime()` therefore probes the API class
`RegionScheduler` (present whenever the API is on the classpath), while
`isFoliaServer()` probes `RegionizedServer` (true only on a running server).
