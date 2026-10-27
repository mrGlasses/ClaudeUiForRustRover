// SKETCH — not compiled. Versions are placeholders; bump to current before first build.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Pass your installed RustRover to compile against it instead of downloading an SDK:
//   ./gradlew buildPlugin -PrustroverPath="C:\Program Files\JetBrains\RustRover 2025.2"
val rustroverPath: String? = providers.gradleProperty("rustroverPath").orNull

dependencies {
    intellijPlatform {
        if (rustroverPath != null) local(rustroverPath) else create("RR", "2025.2") // "RR" = RustRover
        // Gson and pty4j come from the platform.
        bundledPlugin("org.jetbrains.plugins.terminal") // only if you reuse the IDE terminal widget
    }
    // Small standalone WS server, no Netty clash with the platform.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

// No jvmToolchain(): it would force a full JDK 21 (or a download). The build uses whichever JVM runs Gradle,
// e.g. the one bundled with RustRover (see BUILD.md) or the one in CI.
