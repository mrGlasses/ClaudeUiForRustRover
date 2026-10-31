# Precheck: build, tests and plugin verifier before pushing

CI (GitHub Actions) stays the source of truth. This is how Claude checks a change **before** handing you the commit, so
compile errors, failing tests and verifier problems show up here instead of in CI. Nothing here is part of the plugin.

Claude's sandbox can't reach Maven Central, Gradle or JetBrains' repositories, so the check doesn't use Gradle:

1. **Compiler**: `kotlinc` 2.4.21 (same version as `build.gradle.kts`), from the Kotlin GitHub release.
2. **IDE classes**: the jars of *your installed RustRover* (2026.2.4, build 262.10968.211): `lib/intellij.platform.*.jar`,
   `lib/util*.jar`, `lib/annotations.jar`, Gson, Java-WebSocket, coroutines, and `plugins/terminal`, `plugins/jcef-plugin`.
   Read with your permission from `C:\Program Files\JetBrains\RustRover 2024.3.2` (the folder keeps its first version's
   name; `product-info.json` inside says 2026.2.4).
3. **`precheck.sh`**: builds the web panel (typecheck + bundle), compiles `src/main` and `src/test` against those jars,
   runs every unit test (with `junit/`, a small stand-in for JUnit 4 that has the same method signatures), and assembles a
   plugin zip the way `buildPlugin` does (classes, resources, web panel, `<version>` and `since-build="262"` patched into
   plugin.xml, Java-WebSocket in `lib/`).
4. **Plugin verifier**: `verifier-cli-1.411-all.jar` (GitHub release) runs on your computer's Claude sandbox against the
   full RustRover folder: `check-plugin <zip> "<RustRover folder>" -offline`. Always compare the zip's md5 on both sides
   before trusting the result, and give every zip copy a new file name: reusing a name delivered a stale copy twice.

```
tools/precheck/precheck.sh <kotlinc dir> <dir with the IDE jars> [work dir]
```

Differences from CI: Java 21 instead of 25 (`-jvm-target 21`), JUnit stand-in instead of JUnit 4.13.2, and the verifier
checks only your RustRover build (CI's `recommended()` IDE list may include more).
